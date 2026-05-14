package space.aliwasouf.readreplica;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        RoutingTarget target = RoutingContext.get();
        return target == null ? RoutingTarget.MASTER : target;
    }
}
