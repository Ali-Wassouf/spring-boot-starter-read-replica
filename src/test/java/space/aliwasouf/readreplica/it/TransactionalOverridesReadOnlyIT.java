package space.aliwasouf.readreplica.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import space.aliwasouf.readreplica.it.probe.ReadOnlyClassProbeService;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionalOverridesReadOnlyIT extends AbstractReplicationIT {

    @Autowired
    ReadOnlyClassProbeService probe;

    @Test
    void methodLevelTransactionalOverridesClassLevelReadOnly() {
        ReadOnlyClassProbeService.WriteResult result =
                probe.writeAndReport("tx_overrides_check");

        assertThat(result.inRecovery())
                .as("@Transactional should override class-level @ReadOnly and route to master")
                .isFalse();
        assertThat(result.rowCount())
                .as("the write must have succeeded on master")
                .isGreaterThanOrEqualTo(1);
    }
}
