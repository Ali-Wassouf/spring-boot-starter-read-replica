package space.aliwasouf.readreplica;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingContextTest {

    @AfterEach
    void clearContext() {
        RoutingContext.clear();
    }

    @Test
    void getReturnsNullWhenUnset() {
        assertThat(RoutingContext.get()).isNull();
    }

    @Test
    void setAndGetRoundTrip() {
        RoutingContext.set(RoutingTarget.REPLICA);
        assertThat(RoutingContext.get()).isEqualTo(RoutingTarget.REPLICA);

        RoutingContext.set(RoutingTarget.MASTER);
        assertThat(RoutingContext.get()).isEqualTo(RoutingTarget.MASTER);
    }

    @Test
    void clearRemovesValue() {
        RoutingContext.set(RoutingTarget.REPLICA);
        RoutingContext.clear();
        assertThat(RoutingContext.get()).isNull();
    }

    @Test
    void contextIsIsolatedPerThread() throws Exception {
        RoutingContext.set(RoutingTarget.REPLICA);

        RoutingTarget[] observed = new RoutingTarget[1];
        Thread other = new Thread(() -> observed[0] = RoutingContext.get());
        other.start();
        other.join();

        assertThat(observed[0]).isNull();
        assertThat(RoutingContext.get()).isEqualTo(RoutingTarget.REPLICA);
    }
}
