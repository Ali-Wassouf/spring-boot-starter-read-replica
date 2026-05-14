package space.aliwasouf.readreplica.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import space.aliwasouf.readreplica.it.probe.ReadOnlyClassProbeService;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyClassLevelIT extends AbstractReplicationIT {

    @Autowired
    ReadOnlyClassProbeService probe;

    @Test
    void everyMethodOnAClassAnnotatedReadOnlyRoutesToReplica() {
        assertThat(probe.firstRead()).isTrue();
        assertThat(probe.secondRead()).isTrue();
    }
}
