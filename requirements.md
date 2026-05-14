# Spring Boot Read/Write Routing Library — Requirements

## Overview

A Spring Boot 3.x starter library that provides transparent read/write datasource routing
across a write master and a single read replica. Distributed as a standard Maven/Gradle
artifact. Consumers configure both datasources via standard Spring Boot application
properties and opt individual repository methods or classes into replica routing via
a `@ReadOnly` annotation.

---

## Supported Frameworks

- Spring Boot 3.x and above (Jakarta EE namespace)
- Spring Data JPA repositories
- `JdbcTemplate`
- Hibernate as the JPA provider

---

## Datasource Configuration

- Two datasources are configured independently via `application.yml` or `application.properties`
- Each datasource has its own connection pool configuration (pool size, timeouts, etc.)
- The library registers itself via Spring Boot auto-configuration (`AutoConfiguration.imports`)

---

## `@ReadOnly` Annotation

### Purpose

Routes the annotated method or all methods on the annotated class to the read replica
datasource instead of the write master.

### Scope

- Applicable at **method level** and **class level**
- When applied at class level, all methods in that repository route to the replica
- A method-level `@Transactional` annotation on a method overrides a class-level `@ReadOnly`
  — that method routes to the master

### Transaction Interaction

- `@ReadOnly` and transaction management are **explicitly separate concerns**
- `@ReadOnly` does **not** implicitly open a transaction; it only switches the datasource context
- If a `@Transactional` context is already open when a `@ReadOnly` method is called,
  the existing transaction wins — no mid-transaction datasource switching occurs
- Developers who want transactional guarantees on the replica must annotate explicitly
  with `@Transactional(readOnly = true)` in addition to `@ReadOnly`

### Hibernate Convenience

- When `@ReadOnly` is active, the library silently applies `readOnly = true` to the
  Hibernate session, suppressing dirty checking and flush operations on that session

---

## Replica Fallback

- If the read replica is unreachable, all traffic falls back to the write master
- On fallback:
    - A **log warning** is emitted
    - A **Micrometer metric** is recorded (if Micrometer is on the classpath)
- The library **periodically retries** the replica connection to detect recovery
    - Default retry interval: **15 seconds**
    - Configurable via application properties (see Configuration Reference below)
    - A configured value of `0` disables retry entirely; the library stays on master
      until the application restarts
- On replica recovery:
    - A **log message** is emitted confirming the replica is back
    - A **Micrometer metric** is recorded (if Micrometer is on the classpath)
    - Routing resumes to the replica automatically

---

## Observability

### Health Indicators

- The library exposes a **Spring Boot Actuator health indicator** for each datasource
  (master and replica) independently
- Health indicators are registered automatically if Spring Boot Actuator is on the classpath

### Metrics

- Micrometer integration is **optional**
- If Micrometer is present on the classpath, the library activates and emits metrics
- If Micrometer is absent, metric-related behaviour is silently skipped — no errors,
  no warnings
- Metrics include at minimum:
    - Replica fallback events
    - Replica recovery events

---

## Configuration Reference

All properties are prefixed with `datasource.routing` (exact prefix TBD during design).

| Property | Type | Default | Description |
|---|---|---|---|
| `master.url` | String | — | JDBC URL for the write master |
| `master.username` | String | — | Master datasource username |
| `master.password` | String | — | Master datasource password |
| `master.pool.*` | Various | HikariCP defaults | Master connection pool settings |
| `replica.url` | String | — | JDBC URL for the read replica |
| `replica.username` | String | — | Replica datasource username |
| `replica.password` | String | — | Replica datasource password |
| `replica.pool.*` | Various | HikariCP defaults | Replica connection pool settings |
| `replica.retry-interval-seconds` | Integer | `15` | Seconds between replica reconnect attempts. `0` disables retry. |

---

## Distribution

- Distributed as a Maven/Gradle artifact
- Target repository to be decided (Maven Central, private Nexus, or GitHub Packages)
- Packaged as a Spring Boot starter following the `AutoConfiguration.imports` convention
  required by Spring Boot 3.x

---

## Out of Scope (Current Version)

- Multiple read replicas
- Weighted or round-robin replica selection
- Replica lag detection or consistency guarantees
- Spring Boot 2.x / javax namespace support
