package space.aliwasouf.readreplica;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceHealthMonitorTest {

    private DataSourceHealthMonitor monitor;

    @AfterEach
    void destroyMonitor() throws Exception {
        if (monitor != null) monitor.destroy();
    }

    @Test
    void initiallyBothDatasourcesAreHealthy() {
        monitor = buildMonitor(healthy(), healthy(), 100, 100);
        assertThat(monitor.isMasterHealthy()).isTrue();
        assertThat(monitor.isReplicaHealthy()).isTrue();
    }

    @Test
    void replicaFlagFlipsUnhealthyAfterProbe() {
        monitor = buildMonitor(healthy(), broken(), 100, 100);
        awaitReplica(false);
        assertThat(monitor.isMasterHealthy()).isTrue();
    }

    @Test
    void masterFlagFlipsUnhealthyAfterProbe() {
        monitor = buildMonitor(broken(), healthy(), 100, 100);
        awaitMaster(false);
        assertThat(monitor.isReplicaHealthy()).isTrue();
    }

    @Test
    void recoveryFlipsReplicaFlagBackToHealthy() {
        ToggleableDataSource replica = new ToggleableDataSource(false);
        monitor = buildMonitor(healthy(), replica, 100, 100);
        awaitReplica(false);
        replica.setHealthy(true);
        awaitReplica(true);
    }

    @Test
    void zeroIntervalDisablesProbeAndLeavesInitialFlagTrue() throws Exception {
        monitor = buildMonitor(broken(), broken(), 100, 0);
        Thread.sleep(400);
        assertThat(monitor.isReplicaHealthy())
                .as("disabled probe must not flip the healthy flag")
                .isTrue();
        // master probe IS running, so it should eventually flip
        awaitMaster(false);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static DataSourceHealthMonitor buildMonitor(
            DataSource master, DataSource replica, long masterMs, long replicaMs) {
        return new DataSourceHealthMonitor(master, replica, masterMs, replicaMs, MILLISECONDS);
    }

    private void awaitReplica(boolean expected) {
        Awaitility.await().atMost(3, SECONDS).pollInterval(50, MILLISECONDS)
                .untilAsserted(() -> assertThat(monitor.isReplicaHealthy()).isEqualTo(expected));
    }

    private void awaitMaster(boolean expected) {
        Awaitility.await().atMost(3, SECONDS).pollInterval(50, MILLISECONDS)
                .untilAsserted(() -> assertThat(monitor.isMasterHealthy()).isEqualTo(expected));
    }

    @SuppressWarnings("unchecked")
    private static DataSource healthy() {
        try {
            Connection conn = mock(Connection.class);
            when(conn.isValid(1)).thenReturn(true);
            DataSource ds = mock(DataSource.class);
            when(ds.getConnection()).thenReturn(conn);
            return ds;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static DataSource broken() {
        try {
            DataSource ds = mock(DataSource.class);
            when(ds.getConnection()).thenThrow(new SQLException("simulated failure"));
            return ds;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    static class ToggleableDataSource implements DataSource {
        private final AtomicBoolean healthy;

        ToggleableDataSource(boolean initially) {
            this.healthy = new AtomicBoolean(initially);
        }

        void setHealthy(boolean v) {
            healthy.set(v);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (!healthy.get()) throw new SQLException("simulated failure");
            try {
                Connection conn = mock(Connection.class);
                when(conn.isValid(1)).thenReturn(true);
                return conn;
            } catch (Exception e) {
                throw new SQLException(e);
            }
        }

        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int s) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
