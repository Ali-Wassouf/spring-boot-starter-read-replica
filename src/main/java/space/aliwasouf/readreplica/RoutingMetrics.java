package space.aliwasouf.readreplica;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class RoutingMetrics implements DataSourceHealthMonitor.Listener {

    private static final String HEALTHY   = "datasource.routing.healthy";
    private static final String UNHEALTHY = "datasource.routing.unhealthy";
    private static final String RECOVERED = "datasource.routing.recovered";

    private final MeterRegistry registry;
    private final Map<RoutingTarget, AtomicInteger> healthGauges = new EnumMap<>(RoutingTarget.class);

    public RoutingMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (RoutingTarget target : RoutingTarget.values()) {
            String tag = target.name().toLowerCase();

            // Gauge: 1 = healthy, 0 = unhealthy. Starts at 1 (assumed healthy on startup).
            AtomicInteger gaugeValue = new AtomicInteger(1);
            healthGauges.put(target, gaugeValue);
            Gauge.builder(HEALTHY, gaugeValue, AtomicInteger::get)
                    .tag("target", tag)
                    .description("1 if the datasource is reachable, 0 if not. "
                            + "Alert on master=0 (writes broken) vs replica=0 (degraded reads).")
                    .register(registry);

            // Counters: track how many times each transition has occurred.
            Counter.builder(UNHEALTHY).tag("target", tag).register(registry);
            Counter.builder(RECOVERED).tag("target", tag).register(registry);
        }
    }

    @Override
    public void onTransition(RoutingTarget target, boolean nowHealthy) {
        healthGauges.get(target).set(nowHealthy ? 1 : 0);
        registry.counter(nowHealthy ? RECOVERED : UNHEALTHY,
                "target", target.name().toLowerCase()).increment();
    }
}
