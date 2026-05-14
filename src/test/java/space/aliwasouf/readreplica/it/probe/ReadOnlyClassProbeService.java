package space.aliwasouf.readreplica.it.probe;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.aliwasouf.readreplica.ReadOnly;

@Service
@ReadOnly
public class ReadOnlyClassProbeService {

    private final JdbcTemplate jdbc;

    public ReadOnlyClassProbeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean firstRead() {
        return inRecovery();
    }

    public boolean secondRead() {
        return inRecovery();
    }

    /**
     * Method-level {@code @Transactional} (write) overrides the class-level
     * {@code @ReadOnly} — this must execute on master.
     */
    @Transactional
    public WriteResult writeAndReport(String table) {
        jdbc.execute("create table if not exists " + table + " (id int)");
        jdbc.update("insert into " + table + " (id) values (1)");
        boolean recovery = inRecovery();
        Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return new WriteResult(recovery, count == null ? 0 : count);
    }

    private boolean inRecovery() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select pg_is_in_recovery()", Boolean.class));
    }

    public record WriteResult(boolean inRecovery, int rowCount) {
    }
}
