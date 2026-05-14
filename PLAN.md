# Implementation Plan — Spring Boot Read/Write Routing Starter

This document describes how to move from the bootstrapped skeleton to a
shippable `0.1.0` release of the `space.aliwasouf:spring-boot-starter-read-replica`
starter. It is the working roadmap for the requirements captured in
[`requirements.md`](requirements.md).

---

## 1. What is already in place (bootstrap)

| Item | Path | Purpose |
|---|---|---|
| Maven build | `pom.xml` | Spring Boot 3.3.x, Java 17, optional Actuator + Micrometer, Testcontainers + Postgres for ITs, Failsafe wired for `*IT.java`. |
| Package skeleton | `src/main/java/space/aliwasouf/readreplica/` | Empty package — implementation classes land here. |
| Test source roots | `src/test/java/...`, `src/test/resources/` | Ready for unit and integration tests. |
| Docker master+replica | `docker/docker-compose.yml` | Bitnami Postgres 16 with streaming replication. Used for both local dev and integration tests. |
| `.gitignore` | `.gitignore` | Maven/IntelliJ/macOS exclusions. |

> **Not yet present (intentionally):** any auto-configuration class, beans,
> `@ReadOnly` annotation, aspects, routing datasource, or
> `META-INF/spring/...AutoConfiguration.imports`. Those are the
> implementation phases below.

To verify the bootstrap before starting work:

```bash
mvn -q -DskipTests package
```

---

## 2. Architecture at a glance

```
┌─────────────────────────────────────────────────────────────┐
│ Consumer code                                                │
│   @ReadOnly        ──────────┐                              │
│   @Transactional   ──────────┤                              │
│   plain repo call  ──────────┤                              │
└──────────────────────────────┼──────────────────────────────┘
                               ▼
              ┌───────────────────────────────┐
              │ ReadOnlyAspect                │
              │   (sets ThreadLocal flag)     │
              └───────────────┬───────────────┘
                              ▼
              ┌───────────────────────────────┐
              │ RoutingContext (ThreadLocal)  │
              │   target = MASTER | REPLICA   │
              └───────────────┬───────────────┘
                              ▼
              ┌───────────────────────────────┐
              │ RoutingDataSource              │
              │   extends                      │
              │   AbstractRoutingDataSource    │
              │   determineCurrentLookupKey()  │
              │     → MASTER unless            │
              │       (context == REPLICA      │
              │        AND replica healthy)    │
              └─────┬──────────────────┬──────┘
                    ▼                  ▼
           ┌─────────────┐    ┌─────────────┐
           │ Master DS   │    │ Replica DS  │
           │ (Hikari)    │    │ (Hikari)    │
           └─────────────┘    └─────────────┘
                    ▲                  ▲
                    │                  │
              ┌─────┴──────────────────┴─────┐
              │ DataSourceHealthMonitor       │
              │   - scheduled probe of BOTH   │
              │     master and replica         │
              │   - per-datasource healthy flag│
              │   - emits log + metric on each │
              │     state transition           │
              │                                │
              │   Replica unhealthy → routing  │
              │   falls back to master.        │
              │   Master unhealthy → observable│
              │   only (writes have no other   │
              │   target; they will fail loudly│
              │   at the JDBC layer).          │
              └────────────────────────────────┘
```

Key design choices to lock in early:

1. **Routing key is a ThreadLocal `RoutingContext.target`** — set by the
   `@ReadOnly` aspect, cleared in a `finally`. This works for the
   non-reactive Spring stack the requirements call out.
2. **`AbstractRoutingDataSource` is wrapped by a `LazyConnectionDataSourceProxy`**.
   Without that proxy, the connection is acquired (and the routing key
   evaluated) too early — before the `@ReadOnly` aspect has a chance to
   set it. This is a well-known footgun.
3. **`@Transactional` precedence is structural, not detected by us.** A
   method-level `@Transactional` will already have opened the transaction
   (and pinned a connection on the master) before our aspect runs, so the
   existing transaction simply wins. We don't need to detect it; we just
   need to make sure the aspect order is configured so this happens.
   Verified by integration test, not by aspect logic.
4. **Hibernate read-only session** is applied via
   `Session.setDefaultReadOnly(true)` inside the aspect when a JPA
   `EntityManager` is bound to the thread — only when `@ReadOnly` is
   active. Suppresses dirty checking and flushing for the duration of
   the call.
5. **Both datasources are monitored**, by a single
   `DataSourceHealthMonitor` that tracks per-target health (`MASTER`
   and `REPLICA`) as a single source of truth. Health indicators,
   metrics, and routing all read from it so they never disagree.
   The effect of an unhealthy flag differs by target:
   - **Replica unhealthy** → `determineCurrentLookupKey()` falls back
     to master.
   - **Master unhealthy** → no routing change is possible (replica
     is read-only). The monitor still emits logs, metrics, and flips
     the master health indicator; writes will fail at the JDBC layer
     with their normal exception, which is the correct behaviour.

---

## 3. Configuration property design

Prefix: `spring.datasource.routing`.

> Note: this nests under Spring Boot's `spring.datasource` namespace,
> but `@ConfigurationProperties` binds by class, not by shared prefix —
> Boot's own `DataSourceProperties` and our `RoutingProperties` won't
> collide at runtime.

```yaml
spring:
  datasource:
    routing:
      master:
        url: jdbc:postgresql://localhost:5432/appdb
        username: app
        password: app_pw
        pool:                          # forwarded verbatim to HikariConfig
          maximum-pool-size: 10
          connection-timeout: 30000
        probe-interval-seconds: 15     # health probe cadence; observability only
                                       # (writes have no fallback target).
                                       # 0 disables the probe.
      replica:
        url: jdbc:postgresql://localhost:5433/appdb
        username: app
        password: app_pw
        pool:
          maximum-pool-size: 10
        retry-interval-seconds: 15     # health probe cadence; also gates fallback
                                       # recovery. 0 disables the probe — once the
                                       # replica is observed unhealthy, traffic
                                       # stays on master until JVM restart.
```

Bound by a single `@ConfigurationProperties("spring.datasource.routing")` record
tree. Properties metadata is generated automatically thanks to
`spring-boot-configuration-processor` already on the build.

---

## 4. Implementation roadmap

Each phase is a self-contained, testable slice. Land them in order; do
not start a phase before its predecessor has a passing integration
test.

### Phase 1 — Foundation (datasources + routing skeleton)

- [ ] `RoutingProperties` (record-based `@ConfigurationProperties`).
- [ ] `RoutingTarget` enum (`MASTER`, `REPLICA`).
- [ ] `RoutingContext` — ThreadLocal holder with `set/get/clear`.
- [ ] `RoutingDataSource extends AbstractRoutingDataSource` — for now
      always returns `MASTER` (no fallback logic yet, no `@ReadOnly`
      aspect yet).
- [ ] `ReadReplicaAutoConfiguration` (`@AutoConfiguration`) wiring:
      - master Hikari `DataSource`
      - replica Hikari `DataSource`
      - `RoutingDataSource` as the primary `DataSource`
      - `LazyConnectionDataSourceProxy` wrapper
- [ ] `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
      pointing at `ReadReplicaAutoConfiguration`.
- [ ] **Exit test:** consumer app with both datasources configured starts
      up; queries hit the master.

### Phase 2 — `@ReadOnly` annotation + aspect

- [ ] `@ReadOnly` annotation (`@Target({METHOD, TYPE})`, `@Retention(RUNTIME)`).
- [ ] `ReadOnlyAspect` — around advice that:
      - Resolves effective annotation (method beats class).
      - Skips switching if a `@Transactional` (write) context is already active.
      - Pushes `RoutingContext` to `REPLICA`.
      - Wraps Hibernate session as read-only when JPA is on the classpath.
      - Restores prior context in `finally`.
- [ ] Set aspect order to `LOWEST_PRECEDENCE - 10` (slightly higher
      precedence than `@Transactional`) **and** inspect the merged
      `@Transactional` annotation + `TransactionSynchronizationManager`
      to enforce "write tx wins". See the risks table for why ordering
      alone is insufficient.
- [ ] **Exit test:** `@ReadOnly` repo method reads from replica;
      method-level `@Transactional` overrides class-level `@ReadOnly`
      (writes go to master); class-level `@ReadOnly` applies to all methods.

### Phase 3 — Health monitoring (master + replica) and replica fallback

- [ ] `DataSourceHealthMonitor` bean. Single scheduled task per
      datasource (master and replica), each probing
      `Connection.isValid(timeout)`. Replica probe runs every
      `replica.retry-interval-seconds`; master probe runs on the same
      cadence by default but is configurable independently (see
      `master.probe-interval-seconds` in §3).
- [ ] Per-target state machine: `HEALTHY → UNHEALTHY → HEALTHY`. Each
      transition emits:
      - `WARN` log on `HEALTHY → UNHEALTHY` (tagged with target name).
      - `INFO` log on `UNHEALTHY → HEALTHY` (tagged with target name).
- [ ] `RoutingDataSource.determineCurrentLookupKey()` returns `MASTER`
      when context is `REPLICA` but the **replica** is unhealthy.
      Master health does **not** influence routing — writes have no
      alternate target and must fail loudly.
- [ ] Honour `replica.retry-interval-seconds: 0` (no scheduled probe
      for the replica; once observed unhealthy at startup, stays on
      master until JVM restart). The master probe is unaffected by
      this setting.
- [ ] **Exit tests:**
      - Stop replica container → `@ReadOnly` traffic falls back to
        master; restart replica → routing resumes after the retry
        interval.
      - Stop master container → master monitor flips to unhealthy
        within the probe interval, write attempts throw with a
        connection error, replica reads still succeed.

### Phase 4 — Observability (symmetric for master and replica)

- [ ] `MasterDataSourceHealthIndicator`, `ReplicaDataSourceHealthIndicator`
      (`@ConditionalOnClass(HealthIndicator.class)` + matching
      `@ConditionalOnEnabledHealthIndicator`). Both read from
      `DataSourceHealthMonitor` so the actuator endpoint never
      disagrees with routing.
- [ ] `RoutingMetrics` — Micrometer counters, tagged by `target`
      (`master` | `replica`) for symmetry:
      - `datasource.routing.unhealthy{target=…}` on `HEALTHY → UNHEALTHY`.
      - `datasource.routing.recovered{target=…}` on `UNHEALTHY → HEALTHY`.
      Whole class guarded by `@ConditionalOnClass(MeterRegistry.class)`.
- [ ] Wire both into the `DataSourceHealthMonitor` transitions (one
      callback hook, called for both targets).
- [ ] **Exit test:** flipping replica health flips its actuator health
      entry and increments its counters; same for master, independently.

### Phase 5 — Documentation + release prep

- [ ] `README.md` with quick-start, configuration reference, and
      transactional semantics caveats.
- [ ] Decide distribution target (Maven Central / GitHub Packages /
      private Nexus) — `pom.xml` distributionManagement section.
- [ ] CI workflow that runs unit + integration tests (Testcontainers
      requires a Docker-enabled runner).

---

## 5. Testing strategy

### Layering

| Layer | Tool | What it covers |
|---|---|---|
| Unit | JUnit 5 + Mockito | Pure logic: `RoutingContext` push/pop, aspect annotation resolution, monitor state machine. No Spring context, no DB. |
| Slice | `@SpringBootTest` against an in-memory H2 wrapped in `RoutingDataSource` | Wiring sanity: autoconfig produces the right beans, properties bind. No replication semantics. |
| **Integration (the core of this plan)** | Testcontainers `ComposeContainer` driving `docker/docker-compose.yml` | Real Postgres master + streaming replica. Verifies routing, fallback, recovery, metrics, health. |

Unit and slice tests run under Surefire (`mvn test`).
Integration tests are named `*IT.java` and run under Failsafe
(`mvn verify`). The Surefire `<excludes>` in `pom.xml` keeps `*IT.java`
out of `mvn test`.

### Integration test harness

Single base class used by every IT:

```java
@SpringBootTest(classes = TestApp.class)
@Testcontainers
abstract class AbstractReplicationIT {

    @Container
    static final ComposeContainer COMPOSE = new ComposeContainer(
            new File("docker/docker-compose.yml"))
        .withExposedService("postgres-master",   5432, Wait.forListeningPort())
        .withExposedService("postgres-replica",  5432, Wait.forListeningPort())
        .withLocalCompose(true);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String masterHost  = COMPOSE.getServiceHost("postgres-master", 5432);
        int    masterPort  = COMPOSE.getServicePort("postgres-master", 5432);
        String replicaHost = COMPOSE.getServiceHost("postgres-replica", 5432);
        int    replicaPort = COMPOSE.getServicePort("postgres-replica", 5432);

        r.add("spring.datasource.routing.master.url",
              () -> "jdbc:postgresql://" + masterHost + ":" + masterPort + "/appdb");
        r.add("spring.datasource.routing.master.username", () -> "app");
        r.add("spring.datasource.routing.master.password", () -> "app_pw");
        r.add("spring.datasource.routing.replica.url",
              () -> "jdbc:postgresql://" + replicaHost + ":" + replicaPort + "/appdb");
        r.add("spring.datasource.routing.replica.username", () -> "app");
        r.add("spring.datasource.routing.replica.password", () -> "app_pw");
        r.add("spring.datasource.routing.replica.retry-interval-seconds", () -> "2");
    }
}
```

Notes on the harness:

- One compose stack per test JVM (`@Container` on a `static` field).
  Use Awaitility to wait for replication lag to settle inside tests
  rather than between them.
- `retry-interval-seconds: 2` keeps the fallback/recovery test fast.

### How to prove "this query went to the replica"

Postgres makes this easy: **`pg_is_in_recovery()`** returns `true` on a
hot standby and `false` on a primary. The integration tests should
include a tiny native query that calls it through whatever path is
under test, and assert which side served the query.

```java
@ReadOnly
public boolean wasServedByReplica() {
    return jdbcTemplate.queryForObject(
        "select pg_is_in_recovery()", Boolean.class);
}
```

This is more robust than relying on logs or connection metadata.

### Integration test matrix (each is one `*IT.java`)

| # | Scenario | What it asserts |
|---|---|---|
| 1 | `RoutingDefaultsIT` | No `@ReadOnly` → `pg_is_in_recovery()` is `false` (master). |
| 2 | `ReadOnlyMethodIT` | Method `@ReadOnly` → `pg_is_in_recovery()` is `true` (replica). |
| 3 | `ReadOnlyClassLevelIT` | Class `@ReadOnly` → every method routes to replica. |
| 4 | `TransactionalOverridesReadOnlyIT` | Class `@ReadOnly` + method `@Transactional` → routes to master; write succeeds. |
| 5 | `ReadOnlyPlusTransactionalReadOnlyIT` | `@ReadOnly` + `@Transactional(readOnly=true)` → routes to replica inside a transaction; write attempt throws. |
| 6 | `WriteThenReadConsistencyIT` | Documents the expected behaviour: a write followed by a `@ReadOnly` read may not see the write until replication catches up. Uses Awaitility to wait for replication. |
| 7 | `ReplicaFallbackIT` | Stop replica container → `@ReadOnly` reads go to master, WARN log + Micrometer counter emitted. |
| 8 | `ReplicaRecoveryIT` | After fallback, restart replica → within `retry-interval-seconds` routing resumes; INFO log + recovery counter emitted. |
| 9 | `RetryDisabledIT` | `retry-interval-seconds: 0` + replica down → traffic stays on master, no scheduled task is registered. |
| 10 | `HibernateReadOnlySessionIT` | `@ReadOnly` JPA call: entity loaded, mutated in-memory, session ends — no `UPDATE` is issued (dirty checking suppressed). |
| 11 | `HealthIndicatorIT` | `/actuator/health` shows both datasources independently; replica down flips replica indicator to `DOWN` without flipping master. |
| 12 | `NestedReadOnlyIT` | Calling a `@ReadOnly` method from within another `@ReadOnly` method behaves correctly (no leak of context when inner returns). |
| 13 | `MasterDownObservabilityIT` | Stop master container → master health indicator flips to `DOWN`, `datasource.routing.unhealthy{target=master}` counter increments, WARN log emitted. Replica reads (`@ReadOnly`) continue to succeed. Restart master → recovery counter increments and indicator returns to `UP`. |
| 14 | `WriteFailsWhenMasterDownIT` | With master stopped, a write call fails with the underlying JDBC connection exception (the library does **not** swallow it or attempt to route writes to the replica). |

Tests 7 and 8 use `COMPOSE.getContainerByServiceName("postgres-replica_1")` and `DockerClientFactory` to stop/start the replica container at runtime. This is the dirtiest test pattern in the suite; isolate it carefully — running it in its own class avoids polluting state for other tests.

### What we deliberately don't test

- Replication lag tuning.
- Multiple replicas, weighted selection — explicitly out of scope per requirements.
- Spring Boot 2.x — out of scope.

---

## 6. Risks and open questions

| Risk | Mitigation |
|---|---|
| `AbstractRoutingDataSource` evaluates the lookup key before the aspect runs in some call paths. | Wrap with `LazyConnectionDataSourceProxy` (called out in §2). Cover with integration test #1. |
| Aspect order vs `@Transactional` interceptor. | `@Transactional` defaults to `Ordered.LOWEST_PRECEDENCE` (innermost), so a pure-ordering solution can't put it strictly outside ours. Instead, `ReadOnlyAspect` runs at `LOWEST_PRECEDENCE - 10` (slightly higher precedence than `@Transactional`) and **inspects** the merged `@Transactional` annotation + `TransactionSynchronizationManager` to skip routing when a write tx is in scope. Integration tests #4 (same-method `@Transactional` overrides class `@ReadOnly`) and outer-tx scenarios are the regression net. |
| Bitnami image env vars drift between major versions. | Pinned to `bitnamilegacy/postgresql:16` (Bitnami consolidated public versioned images under the `bitnamilegacy/*` namespace in late 2025). Revisit on a Postgres major upgrade. |
| Health indicator naming may collide with Spring Boot's auto-registered `DataSourceHealthIndicator`. | Register under explicit names (`masterDb`, `replicaDb`) and disable Boot's default for the routing primary. |
| Async / reactive call paths. | Out of scope for `0.1.0`. Document the ThreadLocal limitation in the README. |

Open questions to resolve before `0.1.0`:

- [ ] Distribution target (Maven Central vs GitHub Packages).
- [ ] Whether to publish a `-tests` jar with the abstract IT harness so
      consumers can reuse it.

---

## 7. Suggested order of work for the next session

1. Phase 1 in full (foundation + autoconfig + IT #1 green).
2. Phase 2 in full (annotation + aspect + ITs #2–#5 green).
3. Phase 3 (fallback + ITs #7–#9 green).
4. Phase 4 (observability + ITs #10–#11 green).
5. Documentation, then publish.
