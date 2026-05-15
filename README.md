# spring-boot-starter-read-replica

A Spring Boot 3.x starter that provides **transparent read/write datasource routing** between a write master and a read replica. Add the dependency, configure two datasources, and annotate your service methods with `@ReadOnly` — no boilerplate required.

[![Maven Central](https://img.shields.io/maven-central/v/space.aliwassouf/spring-boot-starter-read-replica)](https://central.sonatype.com/artifact/space.aliwassouf/spring-boot-starter-read-replica)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)

---

## Requirements

- Java 17+
- Spring Boot 3.x (Jakarta EE namespace)
- A PostgreSQL master + read replica (or any JDBC-compatible replication setup)

---

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>space.aliwassouf</groupId>
    <artifactId>spring-boot-starter-read-replica</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. Configure your datasources

```yaml
spring:
  datasource:
    routing:
      master:
        url: jdbc:postgresql://localhost:5432/mydb
        username: app
        password: secret
      replica:
        url: jdbc:postgresql://localhost:5433/mydb
        username: app
        password: secret
```

### 3. Annotate your service methods

```java
@Service
public class OrderService {

    private final OrderRepository repository;

    // Routes to the read replica
    @ReadOnly
    public List<Order> findAll() {
        return repository.findAll();
    }

    // Routes to the master — writes as normal
    @Transactional
    public Order place(Order order) {
        return repository.save(order);
    }
}
```

That's it. No extra configuration, no `DataSource` beans to declare.

---

## How routing works

Every request is routed to the **master** by default. The `@ReadOnly` annotation switches the active datasource to the replica for the duration of the annotated method.

```
plain call            →  MASTER
@ReadOnly             →  REPLICA
@Transactional        →  MASTER  (write transaction; overrides @ReadOnly)
@ReadOnly
@Transactional(readOnly = true)  →  REPLICA  (transactional read on replica)
```

Routing is backed by a `ThreadLocal` context and Spring's `AbstractRoutingDataSource`, wrapped in a `LazyConnectionDataSourceProxy` so the routing decision is deferred until the first actual JDBC operation.

---

## `@ReadOnly` annotation

### Method level

```java
@ReadOnly
public Product findById(Long id) { ... }   // replica
public Product save(Product p)   { ... }   // master (no annotation)
```

### Class level

Applying `@ReadOnly` to a class routes **all methods** to the replica.

```java
@Service
@ReadOnly
public class ReportingService {
    public List<Report> monthly() { ... }  // replica
    public List<Report> weekly()  { ... }  // replica
}
```

A method-level `@Transactional` (write) on a `@ReadOnly` class still routes to the master — the write transaction wins:

```java
@Service
@ReadOnly
public class ProductService {
    public List<Product> findAll() { ... }  // replica

    @Transactional              // overrides class-level @ReadOnly
    public Product save(...) { ... }        // master
}
```

### Transaction interaction

`@ReadOnly` and transaction management are **separate concerns**:

| Annotation | Transaction | Routing |
|---|---|---|
| `@ReadOnly` | None opened | Replica |
| `@Transactional` | Write transaction | Master |
| `@ReadOnly` + `@Transactional(readOnly = true)` | Read-only transaction | Replica |
| Class `@ReadOnly` + method `@Transactional` | Write transaction | Master |

**Important caveats:**

1. **No mid-transaction switching.** If a `@Transactional` context is already open when a `@ReadOnly` method is called (e.g., from an outer service), the existing transaction wins — no datasource switch occurs. This is intentional: switching datasources mid-transaction is undefined behaviour.

2. **`@ReadOnly` does not open a transaction.** It only switches the datasource context. If you need transactional guarantees on the replica, annotate explicitly with `@Transactional(readOnly = true)` **in addition** to `@ReadOnly`.

3. **Replication lag.** A write followed immediately by a `@ReadOnly` read may not see the just-written data until replication catches up. If read-your-writes consistency is required, use the master for that read (omit `@ReadOnly`).

4. **Self-invocation.** Like all Spring AOP advice, `@ReadOnly` only takes effect when the call goes through the Spring proxy. Calling an annotated method from within the same class bypasses the proxy and routing will not switch.

### Hibernate session

When `@ReadOnly` is active and a Hibernate session is bound to the current thread (i.e., inside a `@Transactional(readOnly = true)` scope), the starter silently sets `Session.setDefaultReadOnly(true)`. This suppresses Hibernate's dirty-checking and prevents any accidental flush of mutations to the replica.

---

## Configuration reference

All properties are under the `spring.datasource.routing` prefix.

```yaml
spring:
  datasource:
    routing:
      master:
        url:                          # JDBC URL for the write master
        username:                     # Master username
        password:                     # Master password
        probe-interval-seconds: 15    # Health probe interval. 0 = disabled.
        pool:                         # Forwarded verbatim to HikariCP
          maximum-pool-size: 10
          connection-timeout: 30000

      replica:
        url:                          # JDBC URL for the read replica
        username:                     # Replica username
        password:                     # Replica password
        retry-interval-seconds: 15    # Health probe + recovery interval. 0 = disabled.
        pool:                         # Forwarded verbatim to HikariCP
          maximum-pool-size: 10
          connection-timeout: 30000
```

Any valid [HikariCP property](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby) can be placed under `pool.*` using kebab-case names (e.g., `maximum-pool-size`, `connection-timeout`).

---

## Replica fallback

If the replica becomes unreachable, all traffic (including `@ReadOnly` calls) automatically falls back to the master. The library periodically re-probes the replica; when it recovers, routing resumes automatically.

```
Replica unreachable  →  WARN log + datasource.routing.unhealthy{target=replica} counter incremented
                         All @ReadOnly traffic falls back to MASTER

Replica recovered    →  INFO log + datasource.routing.recovered{target=replica} counter incremented
                         @ReadOnly traffic resumes on REPLICA
```

The master is monitored in the same way. When the master becomes unreachable:
- A WARN log is emitted and `datasource.routing.unhealthy{target=master}` is incremented.
- Replica reads continue to work normally.
- Write attempts throw the underlying JDBC connection exception — there is no alternate write target to fall back to.

Probe intervals are configurable independently per datasource. Setting `retry-interval-seconds: 0` (replica) or `probe-interval-seconds: 0` (master) disables the probe entirely; once an instance is observed as unhealthy at startup the state stays until the JVM restarts.

---

## Observability

### Health indicators (Spring Boot Actuator)

If `spring-boot-starter-actuator` is on the classpath, the library registers two independent health indicators:

```
GET /actuator/health
{
  "components": {
    "masterDb":  { "status": "UP" },
    "replicaDb": { "status": "UP" }
  }
}
```

Both indicators read from the same `DataSourceHealthMonitor` that drives routing, so the health endpoint always agrees with the actual routing decisions.

Enable detail visibility in `application.yml`:
```yaml
management:
  endpoint:
    health:
      show-details: always
```

### Micrometer metrics

If Micrometer is on the classpath, three sets of meters are registered (all tagged by `target=master` or `target=replica`):

| Metric | Type | Description |
|---|---|---|
| `datasource.routing.healthy{target}` | Gauge | `1.0` = reachable, `0.0` = unreachable |
| `datasource.routing.unhealthy{target}` | Counter | Number of times the instance went unhealthy |
| `datasource.routing.recovered{target}` | Counter | Number of times the instance recovered |

Recommended alert rules:
```
# Critical — writes are down
datasource.routing.healthy{target="master"} == 0

# Warning — reads degrade to master
datasource.routing.healthy{target="replica"} == 0
```

Expose via the actuator:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics
```

---

## Local development

A Docker Compose file is included at `docker/docker-compose.yml` for spinning up a Postgres master + streaming replica locally:

```bash
# start master (port 5432) + replica (port 5433)
cd docker && docker compose up -d

# stop
cd docker && docker compose down
```

Credentials: `username=app`, `password=app_pw`, `database=appdb`.

---

## Out of scope (v0.1.0)

- Multiple read replicas / weighted routing
- Replica lag detection or read-your-writes consistency guarantees
- Spring Boot 2.x / javax namespace
- Reactive / WebFlux (ThreadLocal-based routing is not compatible with reactive pipelines)

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
