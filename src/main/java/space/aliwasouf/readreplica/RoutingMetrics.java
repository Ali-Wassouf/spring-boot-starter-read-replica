package space.aliwasouf.readreplica;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class RoutingMetrics implements DataSourceHealthMonitor.Listener {

    private static final String UNHEALTHY = "datasource.routing.unhealthy";
    private static final String RECOVERED  = "datasource.routing.recovered";

    private final MeterRegistry registry;

    public RoutingMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Pre-register all tag combinations so dashboards see them from startup.
        for (RoutingTarget target : RoutingTarget.values()) {
            String tag = target.name().toLowerCase();
            Counter.builder(UNHEALTHY).tag("target", tag).register(registry);
            Counter.builder(RECOVERED).tag("target", tag).register(registry);
        }
    }

    @Override
    public void onTransition(RoutingTarget target, boolean nowHealthy) {
        String name = nowHealthy ? RECOVERED : UNHEALTHY;
        registry.counter(name, "target", target.name().toLowerCase()).increment();
    }
}
