package space.aliwasouf.readreplica.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import space.aliwasouf.readreplica.it.probe.PlainProbeService;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyMethodIT extends AbstractReplicationIT {

    @Autowired
    PlainProbeService probe;

    @Test
    void readOnlyAnnotatedMethodRoutesToReplica() {
        assertThat(probe.readOnlyRead())
                .as("@ReadOnly method should be served by the replica")
                .isTrue();
    }

    @Test
    void plainMethodOnSameClassStillRoutesToMaster() {
        assertThat(probe.plainRead())
                .as("un-annotated method on the same class should land on the master")
                .isFalse();
    }
}
