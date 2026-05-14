package space.aliwasouf.readreplica.it.probe;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import space.aliwasouf.readreplica.ReadOnly;

@Service
public class PlainProbeService {

    private final JdbcTemplate jdbc;

    public PlainProbeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean plainRead() {
        return inRecovery();
    }

    @ReadOnly
    public boolean readOnlyRead() {
        return inRecovery();
    }

    private boolean inRecovery() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select pg_is_in_recovery()", Boolean.class));
    }
}
