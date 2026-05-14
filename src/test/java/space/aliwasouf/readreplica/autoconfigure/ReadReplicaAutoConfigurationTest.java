package space.aliwasouf.readreplica.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import space.aliwasouf.readreplica.DataSourceHealthMonitor;
import space.aliwasouf.readreplica.RoutingDataSource;
import space.aliwasouf.readreplica.RoutingTarget;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReadReplicaAutoConfigurationTest {

    /**
     * Pool init is skipped (initialization-fail-timeout=-1) so the slice
     * test runs without Docker or H2. We verify wiring only.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ReadReplicaAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.routing.master.url=jdbc:postgresql://localhost:5432/master",
                    "spring.datasource.routing.master.username=u",
                    "spring.datasource.routing.master.password=p",
                    "spring.datasource.routing.master.pool.initialization-fail-timeout=-1",
                    "spring.datasource.routing.master.probe-interval-seconds=0",
                    "spring.datasource.routing.replica.url=jdbc:postgresql://localhost:5433/replica",
                    "spring.datasource.routing.replica.username=u",
                    "spring.datasource.routing.replica.password=p",
                    "spring.datasource.routing.replica.pool.initialization-fail-timeout=-1",
                    "spring.datasource.routing.replica.retry-interval-seconds=0"
            );

    @Test
    void registersMasterReplicaAndRoutingDataSources() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasBean("masterDataSource");
            assertThat(ctx).hasBean("replicaDataSource");

            DataSource master = ctx.getBean("masterDataSource", DataSource.class);
            DataSource replica = ctx.getBean("replicaDataSource", DataSource.class);
            assertThat(master).isInstanceOf(HikariDataSource.class);
            assertThat(replica).isInstanceOf(HikariDataSource.class);

            DataSource primary = ctx.getBean(DataSource.class);
            assertThat(primary).isInstanceOf(LazyConnectionDataSourceProxy.class);
        });
    }

    @Test
    void routingDataSourceIsBackedByMasterAndReplica() {
        runner.run(ctx -> {
            LazyConnectionDataSourceProxy lazy =
                    (LazyConnectionDataSourceProxy) ctx.getBean(DataSource.class);
            assertThat(lazy.getTargetDataSource()).isInstanceOf(RoutingDataSource.class);
            RoutingDataSource routing = (RoutingDataSource) lazy.getTargetDataSource();
            assertThat(routing.getResolvedDataSources())
                    .containsKeys(RoutingTarget.MASTER, RoutingTarget.REPLICA);
        });
    }

    @Test
    void registersHealthMonitor() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(DataSourceHealthMonitor.class));
    }

    @Test
    void bindsHikariPoolSettingsFromProperties() {
        runner.withPropertyValues("spring.datasource.routing.master.pool.maximum-pool-size=7")
              .run(ctx -> {
                  HikariDataSource master =
                          (HikariDataSource) ctx.getBean("masterDataSource");
                  assertThat(master.getMaximumPoolSize()).isEqualTo(7);
              });
    }
}
