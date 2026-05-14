package space.aliwasouf.readreplica;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import space.aliwasouf.readreplica.autoconfigure.RoutingProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DataSourceHealthMonitor implements DisposableBean {

    /** Notified on every {@code HEALTHY → UNHEALTHY} or {@code UNHEALTHY → HEALTHY} transition. */
    @FunctionalInterface
    public interface Listener {
        void onTransition(RoutingTarget target, boolean nowHealthy);
    }

    private static final Logger log = LoggerFactory.getLogger(DataSourceHealthMonitor.class);
    private static final int PROBE_CONNECTION_TIMEOUT_MS = 3_000;

    private final AtomicBoolean masterHealthy = new AtomicBoolean(true);
    private final AtomicBoolean replicaHealthy = new AtomicBoolean(true);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "datasource-health-monitor");
        t.setDaemon(true);
        return t;
    });

    public DataSourceHealthMonitor(RoutingProperties properties) {
        this(
                buildProbeDataSource(
                        properties.master().url(),
                        properties.master().username(),
                        properties.master().password()),
                buildProbeDataSource(
                        properties.replica().url(),
                        properties.replica().username(),
                        properties.replica().password()),
                properties.master().effectiveProbeIntervalSeconds(),
                properties.replica().effectiveRetryIntervalSeconds(),
                TimeUnit.SECONDS);
    }

    /** Package-private — lets unit tests inject mock DataSources and millisecond intervals. */
    DataSourceHealthMonitor(
            DataSource masterProbeDataSource,
            DataSource replicaProbeDataSource,
            long masterInterval,
            long replicaInterval,
            TimeUnit unit) {

        if (masterInterval > 0) {
            scheduler.scheduleWithFixedDelay(
                    () -> probe(masterProbeDataSource, RoutingTarget.MASTER, masterHealthy),
                    masterInterval, masterInterval, unit);
        }
        if (replicaInterval > 0) {
            scheduler.scheduleWithFixedDelay(
                    () -> probe(replicaProbeDataSource, RoutingTarget.REPLICA, replicaHealthy),
                    replicaInterval, replicaInterval, unit);
        }
    }

    public void addTransitionListener(Listener listener) {
        listeners.add(listener);
    }

    private void probe(DataSource probeDataSource, RoutingTarget target, AtomicBoolean healthFlag) {
        boolean nowHealthy;
        try (Connection connection = probeDataSource.getConnection()) {
            nowHealthy = connection.isValid(1);
        } catch (SQLException e) {
            nowHealthy = false;
        }

        boolean wasHealthy = healthFlag.getAndSet(nowHealthy);

        if (wasHealthy && !nowHealthy) {
            log.warn("Datasource [{}] is UNHEALTHY", target);
            listeners.forEach(l -> l.onTransition(target, false));
        } else if (!wasHealthy && nowHealthy) {
            log.info("Datasource [{}] has RECOVERED", target);
            listeners.forEach(l -> l.onTransition(target, true));
        }
    }

    public boolean isMasterHealthy() {
        return masterHealthy.get();
    }

    public boolean isReplicaHealthy() {
        return replicaHealthy.get();
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }

    private static DataSource buildProbeDataSource(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(PROBE_CONNECTION_TIMEOUT_MS);
        config.setInitializationFailTimeout(-1);
        config.setPoolName("datasource-health-probe-" + url.hashCode());
        return new HikariDataSource(config);
    }
}
