package space.aliwasouf.readreplica.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import space.aliwasouf.readreplica.it.probe.TxReadOnlyProbeService;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyPlusTransactionalReadOnlyIT extends AbstractReplicationIT {

    @Autowired
    TxReadOnlyProbeService probe;

    @Test
    void readOnlyWithTransactionalReadOnlyRoutesToReplica() {
        assertThat(probe.transactionalReadOnly())
                .as("@ReadOnly + @Transactional(readOnly=true) should route to the replica")
                .isTrue();
    }
}
