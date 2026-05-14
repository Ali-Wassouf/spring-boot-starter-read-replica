package space.aliwasouf.readreplica.it;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.testcontainers.DockerClientFactory;
import space.aliwasouf.readreplica.DataSourceHealthMonitor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class HealthIndicatorIT extends AbstractReplicationIT {

    @Autowired
    @Qualifier("masterDb")
    HealthIndicator masterDbIndicator;

    @Autowired
    @Qualifier("replicaDb")
    HealthIndicator replicaDbIndicator;

    @Autowired
    DataSourceHealthMonitor healthMonitor;

    @AfterEach
    void ensureContainersRunning() {
        for (String name : List.of("postgres-master", "postgres-replica")) {
            findContainer(name).ifPresent(c -> {
                String status = DockerClientFactory.lazyClient()
                        .inspectContainerCmd(c.getId()).exec().getState().getStatus();
                if ("paused".equals(status)) {
                    DockerClientFactory.lazyClient().unpauseContainerCmd(c.getId()).exec();
                }
            });
        }
        Awaitility.await().atMost(15, SECONDS).until(
                () -> healthMonitor.isMasterHealthy() && healthMonitor.isReplicaHealthy());
    }

    @Test
    void bothIndicatorsUpWhenContainersHealthy() {
        assertThat(masterDbIndicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(replicaDbIndicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void replicaIndicatorFlipsDownIndependentlyOfMaster() {
        findContainer("postgres-replica").ifPresent(c ->
                DockerClientFactory.lazyClient().pauseContainerCmd(c.getId()).exec());

        Awaitility.await().atMost(15, SECONDS).until(() -> !healthMonitor.isReplicaHealthy());

        assertThat(replicaDbIndicator.health().getStatus())
                .as("replica indicator must be DOWN when replica is unreachable")
                .isEqualTo(Status.DOWN);
        assertThat(masterDbIndicator.health().getStatus())
                .as("master indicator must stay UP while only replica is down")
                .isEqualTo(Status.UP);
    }

    @Test
    void masterIndicatorFlipsDownIndependentlyOfReplica() {
        findContainer("postgres-master").ifPresent(c ->
                DockerClientFactory.lazyClient().pauseContainerCmd(c.getId()).exec());

        Awaitility.await().atMost(15, SECONDS).until(() -> !healthMonitor.isMasterHealthy());

        assertThat(masterDbIndicator.health().getStatus())
                .as("master indicator must be DOWN when master is unreachable")
                .isEqualTo(Status.DOWN);
        assertThat(replicaDbIndicator.health().getStatus())
                .as("replica indicator must stay UP while only master is down")
                .isEqualTo(Status.UP);
    }

    private static Optional<com.github.dockerjava.api.model.Container> findContainer(String serviceName) {
        List<com.github.dockerjava.api.model.Container> containers =
                DockerClientFactory.lazyClient().listContainersCmd().exec();
        return containers.stream()
                .filter(c -> Arrays.stream(c.getNames()).anyMatch(n -> n.contains(serviceName)))
                .findFirst();
    }
}
