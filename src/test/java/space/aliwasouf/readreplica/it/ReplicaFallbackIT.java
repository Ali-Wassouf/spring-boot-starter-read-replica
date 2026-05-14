package space.aliwasouf.readreplica.it;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.DockerClientFactory;
import space.aliwasouf.readreplica.DataSourceHealthMonitor;
import space.aliwasouf.readreplica.it.probe.PlainProbeService;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses {@code docker pause}/{@code unpause} instead of stop/start so there
 * is no replication re-synchronisation on recovery — unpause is instant.
 */
class ReplicaFallbackIT extends AbstractReplicationIT {

    @Autowired
    PlainProbeService probe;

    @Autowired
    DataSourceHealthMonitor healthMonitor;

    @AfterEach
    void ensureReplicaRunning() {
        replicaContainer().ifPresent(c -> {
            String status = DockerClientFactory.lazyClient()
                    .inspectContainerCmd(c.getId()).exec().getState().getStatus();
            if ("paused".equals(status)) {
                DockerClientFactory.lazyClient().unpauseContainerCmd(c.getId()).exec();
            }
        });
        Awaitility.await().atMost(15, SECONDS).until(healthMonitor::isReplicaHealthy);
    }

    @Test
    void readOnlyFallsBackToMasterWhenReplicaIsPaused() {
        assertThat(probe.readOnlyRead()).as("baseline: replica is up").isTrue();

        replicaContainer().ifPresent(c ->
                DockerClientFactory.lazyClient().pauseContainerCmd(c.getId()).exec());

        Awaitility.await()
                .atMost(15, SECONDS)
                .until(() -> !healthMonitor.isReplicaHealthy());

        assertThat(probe.readOnlyRead())
                .as("replica is paused: @ReadOnly should fall back to master")
                .isFalse();
    }

    @Test
    void readOnlyResumesOnReplicaAfterUnpause() {
        replicaContainer().ifPresent(c ->
                DockerClientFactory.lazyClient().pauseContainerCmd(c.getId()).exec());

        Awaitility.await().atMost(15, SECONDS).until(() -> !healthMonitor.isReplicaHealthy());

        replicaContainer().ifPresent(c ->
                DockerClientFactory.lazyClient().unpauseContainerCmd(c.getId()).exec());

        Awaitility.await()
                .atMost(15, SECONDS)
                .until(healthMonitor::isReplicaHealthy);

        assertThat(probe.readOnlyRead())
                .as("replica unpaused: @ReadOnly should route back to replica")
                .isTrue();
    }

    private static Optional<com.github.dockerjava.api.model.Container> replicaContainer() {
        List<com.github.dockerjava.api.model.Container> containers =
                DockerClientFactory.lazyClient().listContainersCmd().exec();
        return containers.stream()
                .filter(c -> Arrays.stream(c.getNames())
                        .anyMatch(n -> n.contains("postgres-replica")))
                .findFirst();
    }
}
