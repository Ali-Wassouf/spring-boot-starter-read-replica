package space.aliwasouf.readreplica.it;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.testcontainers.DockerClientFactory;
import space.aliwasouf.readreplica.DataSourceHealthMonitor;
import space.aliwasouf.readreplica.it.probe.PlainProbeService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uses {@code docker pause}/{@code unpause} so recovery is instant — no
 * need to wait for Postgres to restart or replication to re-sync.
 */
class MasterDownObservabilityIT extends AbstractReplicationIT {

    @Autowired
    PlainProbeService probe;

    @Autowired
    DataSourceHealthMonitor healthMonitor;

    @Autowired
    @Qualifier("masterDataSource")
    DataSource masterDataSource;

    @AfterEach
    void ensureMasterRunning() {
        masterContainer().ifPresent(c -> {
            String status = DockerClientFactory.lazyClient()
                    .inspectContainerCmd(c.getId()).exec().getState().getStatus();
            if ("paused".equals(status)) {
                DockerClientFactory.lazyClient().unpauseContainerCmd(c.getId()).exec();
            }
        });
        Awaitility.await().atMost(15, SECONDS).until(healthMonitor::isMasterHealthy);
    }

    @Test
    void masterHealthFlipsUnhealthyWhenPaused() {
        masterContainer().ifPresent(c ->
                DockerClientFactory.lazyClient().pauseContainerCmd(c.getId()).exec());

        Awaitility.await()
                .atMost(15, SECONDS)
                .until(() -> !healthMonitor.isMasterHealthy());

        assertThat(healthMonitor.isMasterHealthy()).isFalse();
        assertThat(healthMonitor.isReplicaHealthy()).isTrue();
    }

    @Test
    void replicaReadsSucceedWhileMasterIsPaused() {
        masterContainer().ifPresent(c ->
                DockerClientFactory.lazyClient().pauseContainerCmd(c.getId()).exec());

        Awaitility.await().atMost(15, SECONDS).until(() -> !healthMonitor.isMasterHealthy());

        assertThat(probe.readOnlyRead())
                .as("@ReadOnly reads should still land on the healthy replica")
                .isTrue();
    }

    @Test
    void writeThrowsWhenMasterIsPaused() {
        masterContainer().ifPresent(c ->
                DockerClientFactory.lazyClient().pauseContainerCmd(c.getId()).exec());

        Awaitility.await().atMost(15, SECONDS).until(() -> !healthMonitor.isMasterHealthy());

        assertThatThrownBy(() -> {
            try (Connection conn = masterDataSource.getConnection()) {
                conn.createStatement().execute("select 1");
            }
        }).as("writes must fail loudly when the master is unreachable")
          .isInstanceOf(Exception.class);
    }

    private static Optional<com.github.dockerjava.api.model.Container> masterContainer() {
        List<com.github.dockerjava.api.model.Container> containers =
                DockerClientFactory.lazyClient().listContainersCmd().exec();
        return containers.stream()
                .filter(c -> Arrays.stream(c.getNames())
                        .anyMatch(n -> n.contains("postgres-master")))
                .findFirst();
    }
}
