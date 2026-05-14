package space.aliwasouf.readreplica;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class RoutingDataSource extends AbstractRoutingDataSource {

    private final DataSourceHealthMonitor healthMonitor;

    public RoutingDataSource(DataSourceHealthMonitor healthMonitor) {
        this.healthMonitor = healthMonitor;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        RoutingTarget target = RoutingContext.get();
        if (target == RoutingTarget.REPLICA && !healthMonitor.isReplicaHealthy()) {
            return RoutingTarget.MASTER;
        }
        return target == null ? RoutingTarget.MASTER : target;
    }
}
