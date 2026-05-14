package space.aliwasouf.readreplica.it;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.time.Duration;

@SpringBootTest(classes = TestApp.class)
public abstract class AbstractReplicationIT {

    private static final String MASTER = "postgres-master";
    private static final String REPLICA = "postgres-replica";

    /**
     * Singleton compose stack — started once per JVM (on first class load)
     * and reused by every {@code *IT} that extends this base. Avoids the
     * thrashing of per-test-class container lifecycles managed by
     * {@code @Testcontainers}, and lets sequential ITs share one Postgres
     * cluster. Ryuk + JVM shutdown handle teardown.
     */
    protected static final ComposeContainer COMPOSE = new ComposeContainer(
                new File("docker/docker-compose.yml"))
            .withExposedService(MASTER, 5432,
                    Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))
            .withExposedService(REPLICA, 5432,
                    Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))
            .withLocalCompose(true);

    static {
        COMPOSE.start();
    }

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry r) {
        String masterHost = COMPOSE.getServiceHost(MASTER, 5432);
        int masterPort = COMPOSE.getServicePort(MASTER, 5432);
        String replicaHost = COMPOSE.getServiceHost(REPLICA, 5432);
        int replicaPort = COMPOSE.getServicePort(REPLICA, 5432);

        r.add("spring.datasource.routing.master.url",
                () -> "jdbc:postgresql://" + masterHost + ":" + masterPort + "/appdb");
        r.add("spring.datasource.routing.master.username", () -> "app");
        r.add("spring.datasource.routing.master.password", () -> "app_pw");

        r.add("spring.datasource.routing.replica.url",
                () -> "jdbc:postgresql://" + replicaHost + ":" + replicaPort + "/appdb");
        r.add("spring.datasource.routing.replica.username", () -> "app");
        r.add("spring.datasource.routing.replica.password", () -> "app_pw");
        // Short probe intervals + connection timeout so fallback/recovery ITs
        // detect state changes quickly. Hikari's default connectionTimeout is
        // 30 s — too slow for health probes; 3 s is still generous for local Docker.
        r.add("spring.datasource.routing.replica.retry-interval-seconds", () -> "2");
        r.add("spring.datasource.routing.master.probe-interval-seconds", () -> "2");
        r.add("spring.datasource.routing.master.pool.connection-timeout", () -> "3000");
        r.add("spring.datasource.routing.replica.pool.connection-timeout", () -> "3000");

        // No JPA entities in the test app — keep Hibernate quiet.
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }
}
