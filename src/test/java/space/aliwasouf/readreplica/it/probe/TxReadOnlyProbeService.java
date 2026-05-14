package space.aliwasouf.readreplica.it.probe;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.aliwasouf.readreplica.ReadOnly;

@Service
public class TxReadOnlyProbeService {

    private final JdbcTemplate jdbc;

    public TxReadOnlyProbeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Both annotations: the {@code readOnly=true} on {@code @Transactional}
     * means it does not block routing to the replica. This must execute
     * on the replica, inside a transaction.
     */
    @ReadOnly
    @Transactional(readOnly = true)
    public boolean transactionalReadOnly() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select pg_is_in_recovery()", Boolean.class));
    }
}
