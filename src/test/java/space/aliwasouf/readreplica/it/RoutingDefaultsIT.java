package space.aliwasouf.readreplica.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 exit test: without any @ReadOnly annotation in play, every
 * query lands on the master. pg_is_in_recovery() returns false on a
 * primary and true on a hot standby — a reliable witness for which
 * node served the call.
 */
class RoutingDefaultsIT extends AbstractReplicationIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void readWithoutAnnotationGoesToMaster() {
        Boolean isReplica = jdbcTemplate.queryForObject(
                "select pg_is_in_recovery()", Boolean.class);
        assertThat(isReplica)
                .as("a plain (un-annotated) read should land on the master")
                .isFalse();
    }

    @Test
    void writeGoesToMaster() {
        jdbcTemplate.execute("create table if not exists phase1_check (id int)");
        jdbcTemplate.update("insert into phase1_check (id) values (?)", 1);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from phase1_check", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }
}
