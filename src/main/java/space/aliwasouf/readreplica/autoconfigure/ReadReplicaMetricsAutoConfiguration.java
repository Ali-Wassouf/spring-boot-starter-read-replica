package space.aliwasouf.readreplica.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import space.aliwasouf.readreplica.DataSourceHealthMonitor;
import space.aliwasouf.readreplica.RoutingMetrics;

/**
 * Separate auto-configuration for Micrometer metrics so that ordering can be
 * declared explicitly after both our own datasource setup AND Spring Boot's
 * meter registry are ready. Keeping this in its own class (rather than a nested
 * static class inside {@link ReadReplicaAutoConfiguration}) is the pattern Spring
 * Boot itself uses for optional observability integrations.
 */
@AutoConfiguration(
        after = ReadReplicaAutoConfiguration.class,
        afterName = "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
public class ReadReplicaMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
    public RoutingMetrics routingMetrics(
            io.micrometer.core.instrument.MeterRegistry meterRegistry,
            DataSourceHealthMonitor monitor) {
        RoutingMetrics metrics = new RoutingMetrics(meterRegistry);
        monitor.addTransitionListener(metrics);
        return metrics;
    }
}
