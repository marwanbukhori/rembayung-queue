# 01 — Project skeleton and Spring Boot basics

**Covers:** Task 1 of the booking domain core plan
**Commit:** `4c1b8aa` — *Add booking-service skeleton with Oracle Testcontainers base*
**Files:** `booking-service/pom.xml`, `BookingServiceApplication.java`,
`application.yml`, `application-test.yml`, `OracleTestBase.java`

Task 1 produced no business logic at all. Its deliverable was a *proof*: that a
real Oracle database starts in a container on this machine (Apple M1, arm64)
and that a Spring Boot test can reach it. Everything else here is scaffolding
in service of that.

---

## The 30-second map

| Java / Spring | JS equivalent |
|---|---|
| Maven (`mvn`) | npm |
| `pom.xml` | `package.json` + `tsconfig.json` + build config, in one file |
| `mvn test` | `npm test` |
| `~/.m2/repository` | `node_modules`, but global to the machine, not per-project |
| `spring-boot-starter-*` | meta-packages / presets (like `react-scripts`) |
| Annotations (`@Service`) | decorators (`@Injectable()`) |
| Spring's DI container | **NestJS** — Nest was explicitly modelled on Spring |
| JPA / Hibernate | Prisma or TypeORM |
| Flyway | Prisma Migrate / Knex migrations |
| `ojdbc11` | the `pg` or `mysql2` driver package |
| JUnit + AssertJ | Jest / Vitest + `expect()` |
| Testcontainers | `testcontainers` (same project, has a Node port) |

The single idea with no good JS analogue is **dependency injection**, covered
below.

---

## `pom.xml` — the package.json

XML is just syntax. Read the structure and ignore how it looks.

### Identity

```xml
<groupId>dev.marwan</groupId>
<artifactId>booking-service</artifactId>
<version>0.1.0-SNAPSHOT</version>
```

One coordinate, equivalent to `@marwan/booking-service@0.1.0`. `groupId` is the
namespace — reverse domain name by convention. `SNAPSHOT` means "in
development, not a fixed release"; the closest JS equivalent is a `-dev`
prerelease tag.

### The parent

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.1</version>
</parent>
```

This has no clean JS equivalent and it does a lot. Primarily it is a **version
catalogue**: a POM published by the Spring team declaring known-compatible
versions for several hundred libraries.

That is why most `<dependency>` blocks in this file carry no `<version>` —
Spring Boot already decided. Imagine `npm install express` resolving to the
exact Express version your framework integration-tested against, transitive
dependencies included. That is the parent's job, and it is why Java dependency
conflicts are milder than they could be.

It also sets build defaults: UTF-8 encoding, the Java release level from
`<java.version>25</java.version>`, and plugin configuration.

> **This bit us in Task 1.** The parent defines a property
> `${testcontainers.version}` = `2.0.5`. Testcontainers renamed its artifacts
> in 2.x (`oracle-free` → `testcontainers-oracle-free`) and moved
> `OracleContainer` from `org.testcontainers.containers` to
> `org.testcontainers.oracle`. The plan was written against 1.x coordinates, so
> the POM and one import had to be adjusted. `${...}` is variable
> interpolation, resolved from the parent.

### Dependency scopes

Same idea as `package.json` dependencies, with an extra dimension:

| scope | meaning | JS |
|---|---|---|
| *(none)* | needed to compile **and** run | `dependencies` |
| `runtime` | needed to run, not to compile | no real equivalent |
| `test` | test-only | `devDependencies` |

`ojdbc11` is `runtime` because our code never imports an Oracle class. We write
against the `java.sql` interfaces and the driver is discovered at startup.

This is worth more than it first appears: **no source file can accidentally
depend on an Oracle-specific API, because the compiler cannot see them.**
Swapping databases stays a configuration change. (This project deliberately
*does* depend on Oracle's `SELECT ... FOR UPDATE` behaviour — but that
dependency lives in SQL and in tests, not in compiled application code.)

### Starters

`spring-boot-starter-data-jpa` is a *starter*: a package containing no code,
only dependencies. One line pulls in Hibernate, the JDBC layer, connection
pooling, transaction management, and Spring's repository support.

`flyway-core` plus `flyway-database-oracle` are separate because Flyway 10 split
per-database support into its own modules.

### Build plugins

```xml
<build>
  <plugins>
    <plugin>
      <artifactId>spring-boot-maven-plugin</artifactId>
    </plugin>
  </plugins>
</build>
```

`pom.xml` is also the build script. There is no `"scripts"` section because
Maven has a fixed lifecycle — `compile` → `test` → `package` → `verify` →
`install` — and plugins hook into its phases. `mvn test` runs every phase up to
and including `test`. You configure the pipeline rather than writing it.

---

## Directory layout is not a choice

```
src/main/java/       ← application source
src/main/resources/  ← application non-code (yml, SQL migrations)
src/test/java/       ← test source
src/test/resources/  ← test non-code
```

Maven **requires** this layout. Nothing in `pom.xml` points at these paths —
that is "convention over configuration", the opposite of the JS world where
every project invents its own structure.

Files under `resources/` are copied onto the *classpath*, which is how
`application.yml` is found at runtime without any path appearing in code.

---

## `BookingServiceApplication.java`

```java
@SpringBootApplication
@EnableScheduling
public class BookingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
```

Thirteen lines that do a great deal.

### Dependency injection

In Express you would write `const svc = new BookingService(db)` and wire the
graph yourself. In Spring you never call `new` on your own components.

You annotate a class `@Service`. At startup Spring scans the codebase, finds it,
inspects its constructor, recursively constructs whatever that constructor
needs, and hands the dependencies over. The registry of managed objects is the
**application context**; the objects themselves are **beans**.

### What `@SpringBootApplication` actually is

Three annotations combined. Two matter here:

**Component scan** — "find annotated classes in my package and below". This
class sits in `dev.marwan.booking`, which is precisely why the plan fixes that
as the base package: anything outside it is invisible to Spring. This is the
most common source of "why is my bean null".

**Auto-configuration** — Spring inspects the classpath and configures what it
finds. Oracle driver present → configure a `DataSource`. Hibernate present →
configure JPA. Flyway present → run migrations at startup. None of this appears
in our code.

The tradeoff is honest and worth stating in an interview: auto-configuration
removes an enormous amount of boilerplate, and when it guesses wrong it is
genuinely hard to debug, because the thing that went wrong was never written
down anywhere. `--debug` prints the auto-configuration report.

### `@EnableScheduling`

Turns on the timer subsystem. Nothing uses it yet. **Task 7's expiry sweeper**
needs it for `@Scheduled(fixedDelayString = "PT30S")`.

---

## `application.yml`

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

booking:
  deposit-cents-per-head: 2000
  hold-ttl: PT10M
```

### `ddl-auto: validate` is a correctness decision, not a preference

Hibernate is capable of generating tables from entity classes (`update` /
`create`). This project forbids that.

**Flyway owns the schema**, via versioned SQL files. Hibernate only *verifies*
at startup that the tables match the entity mappings, and refuses to boot if
they have drifted.

The reason is specific: if Hibernate generated the tables, the
`CHECK (seats_taken <= capacity)` constraint would not exist — and that
database-level constraint is the backstop the entire phase is built around. It
is what makes overselling impossible even if the application logic is wrong.

Framed in JS terms: Prisma's auto-generated schema versus hand-written
migrations. Here, hand-written wins because the schema carries a correctness
guarantee the ORM cannot express.

### `open-in-view: false`

Disables a Spring default that holds a database connection open for the
duration of an HTTP request. Under a traffic spike, connections are the scarce
resource — which is the entire subject of this project.

### The `booking:` block

Our own configuration, not Spring's. `PT10M` is an ISO-8601 duration ("period,
time, 10 minutes"), which Spring parses into a `java.time.Duration`
automatically. Task 4 injects these with
`@Value("${booking.deposit-cents-per-head}")`.

### Profiles

`application-test.yml` is a **profile**. A test class annotated
`@ActiveProfiles("test")` gets that file layered over the base one — similar to
`NODE_ENV=test`, except the files are merged rather than replaced.

---

## `OracleTestBase.java` — the interesting one

```java
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class OracleTestBase {

    @Container
    @ServiceConnection
    static final OracleContainer ORACLE = new OracleContainer(
            DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withDatabaseName("bookingdb")
            .withUsername("booking")
            .withPassword("booking")
            .withStartupTimeout(Duration.ofMinutes(5))
            .withReuse(true);
```

`@SpringBootTest` boots the **entire application context** for the test class.
This is an integration test, not a unit test — there are no mocks anywhere in
this project's test suite, by design.

### The chicken-and-egg problem `@ServiceConnection` solves

Spring needs a JDBC URL at startup. Oracle's container gets a **random host
port**, knowable only after it starts.

`@ServiceConnection` resolves this: Testcontainers starts Oracle, reads back the
mapped port, and contributes a matching `DataSource` before Spring configures
anything. No JDBC URL is hardcoded anywhere in the project. (Before this
annotation existed, you wrote a fiddly `@DynamicPropertySource` method by
hand.)

### Two distinct caching layers

Both matter, and they are frequently confused:

1. **Spring context caching** — every test class here shares one configuration,
   so the application boots *once* for the whole suite, not once per class.
2. **Container reuse** — `withReuse(true)`, combined with
   `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, keeps
   the Oracle container alive *between* `mvn test` runs. This is the 30–60
   seconds of Oracle startup you do not wait for on every run.

Reuse is opt-in on both sides deliberately: it is a developer-machine
convenience, and CI should never use it.

### Why there is a `@BeforeEach` cleanup

```java
@BeforeEach
void cleanDatabase() {
    deleteAllRowsIfTableExists("bookings");
    deleteAllRowsIfTableExists("slots");
}
```

This is *not* in the original plan; it was added deliberately. Container reuse
means the database outlives the test run — so it is still holding the previous
run's rows.

Two concrete failures it prevents:

- Later tasks commit rows with hardcoded natural keys and hardcoded idempotency
  keys. Without cleanup the suite passes once and then fails on every
  subsequent run with unique-constraint violations.
- Task 7's expiry sweeper asserts an *exact global count* of swept bookings.
  That assertion only holds if each test starts from an empty database.

Deletion order is `bookings` then `slots` — bookings holds the foreign key, so
it must go first. The guard narrows to ORA-00942 (*table or view does not
exist*) and rethrows anything else, so it does not silently swallow real
failures during Task 1, when no schema exists yet.

### Why the class is `abstract`

`abstract` means the class cannot be instantiated directly. JUnit therefore
never executes it, which is why Task 1's "failing test" step reported **zero
tests run** rather than a failure. Every later test class does
`extends OracleTestBase` and inherits the container, the Spring context, and
the cleanup.

---

## What was actually proven

`mvn test` compiled everything — which proved the Testcontainers coordinates
resolve — found no runnable test in an abstract class, and exited cleanly. A
direct `docker run` of `gvenzl/oracle-free:23-slim-faststart` then printed
`DATABASE IS READY TO USE!` with no `exec format error`, confirming the image
runs natively on arm64 rather than under emulation.

Nothing has touched a database *through Spring* yet.

**Task 2 is the first end-to-end proof.** `SchemaMigrationTest` extends this
base, forcing the full chain to fire: container starts → `@ServiceConnection`
builds the `DataSource` → Spring boots → Flyway applies
`V1__initial_schema.sql` → Hibernate validates the mappings → the test queries
real Oracle. If `@ServiceConnection` is wired incorrectly, that is where it
fails loudly.
