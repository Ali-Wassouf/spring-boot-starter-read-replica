package space.aliwasouf.readreplica;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingMetricsTest {

    private SimpleMeterRegistry registry;
    private RoutingMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RoutingMetrics(registry);
    }

    @Test
    void allCountersPreRegisteredAtZero() {
        for (RoutingTarget target : RoutingTarget.values()) {
            String tag = target.name().toLowerCase();
            assertThat(counter("datasource.routing.unhealthy", tag).count()).isZero();
            assertThat(counter("datasource.routing.recovered", tag).count()).isZero();
        }
    }

    @Test
    void unhealthyTransitionIncrementsUnhealthyCounter() {
        metrics.onTransition(RoutingTarget.REPLICA, false);

        assertThat(counter("datasource.routing.unhealthy", "replica").count()).isEqualTo(1.0);
        assertThat(counter("datasource.routing.recovered",  "replica").count()).isZero();
        assertThat(counter("datasource.routing.unhealthy", "master").count()).isZero();
    }

    @Test
    void recoveryTransitionIncrementsRecoveredCounter() {
        metrics.onTransition(RoutingTarget.MASTER, true);

        assertThat(counter("datasource.routing.recovered", "master").count()).isEqualTo(1.0);
        assertThat(counter("datasource.routing.unhealthy", "master").count()).isZero();
        assertThat(counter("datasource.routing.recovered", "replica").count()).isZero();
    }

    @Test
    void multipleTransitionsAccumulate() {
        metrics.onTransition(RoutingTarget.REPLICA, false);
        metrics.onTransition(RoutingTarget.REPLICA, true);
        metrics.onTransition(RoutingTarget.REPLICA, false);

        assertThat(counter("datasource.routing.unhealthy", "replica").count()).isEqualTo(2.0);
        assertThat(counter("datasource.routing.recovered",  "replica").count()).isEqualTo(1.0);
    }

    private Counter counter(String name, String targetTag) {
        return registry.counter(name, "target", targetTag);
    }
}
