package space.aliwasouf.readreplica.autoconfigure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import space.aliwasouf.readreplica.DataSourceHealthMonitor;
import space.aliwasouf.readreplica.ReadOnlyAspect;
import space.aliwasouf.readreplica.RoutingDataSource;
import space.aliwasouf.readreplica.RoutingTarget;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(RoutingProperties.class)
public class ReadReplicaAutoConfiguration {

    static final String MASTER_POOL_PREFIX = "spring.datasource.routing.master.pool";
    static final String REPLICA_POOL_PREFIX = "spring.datasource.routing.replica.pool";

    @Bean(name = "masterDataSource")
    @ConditionalOnMissingBean(name = "masterDataSource")
    public DataSource masterDataSource(RoutingProperties properties, Environment environment) {
        RoutingProperties.MasterEndpoint m = properties.master();
        return buildHikari(m.url(), m.username(), m.password(), MASTER_POOL_PREFIX, environment);
    }

    @Bean(name = "replicaDataSource")
    @ConditionalOnMissingBean(name = "replicaDataSource")
    public DataSource replicaDataSource(RoutingProperties properties, Environment environment) {
        RoutingProperties.ReplicaEndpoint r = properties.replica();
        return buildHikari(r.url(), r.username(), r.password(), REPLICA_POOL_PREFIX, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public DataSourceHealthMonitor dataSourceHealthMonitor(RoutingProperties properties) {
        return new DataSourceHealthMonitor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyAspect readOnlyAspect(ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        return new ReadOnlyAspect(entityManagerFactory);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "dataSource")
    public DataSource dataSource(
            @Qualifier("masterDataSource") DataSource masterDataSource,
            @Qualifier("replicaDataSource") DataSource replicaDataSource,
            DataSourceHealthMonitor healthMonitor) {

        RoutingDataSource routing = new RoutingDataSource(healthMonitor);
        Map<Object, Object> targets = new HashMap<>();
        targets.put(RoutingTarget.MASTER, masterDataSource);
        targets.put(RoutingTarget.REPLICA, replicaDataSource);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(masterDataSource);
        routing.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routing);
    }

    // ── Actuator health indicators ────────────────────────────────────────────
    // Nested static class so HealthIndicator references are never loaded when
    // spring-boot-starter-actuator is absent from the consumer's classpath.

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    static class ActuatorConfiguration {

        @Bean(name = "masterDb")
        @ConditionalOnMissingBean(name = "masterDb")
        public HealthIndicator masterDbHealthIndicator(
                DataSourceHealthMonitor monitor) {
            return () -> monitor.isMasterHealthy()
                    ? Health.up().build()
                    : Health.down().build();
        }

        @Bean(name = "replicaDb")
        @ConditionalOnMissingBean(name = "replicaDb")
        public HealthIndicator replicaDbHealthIndicator(
                DataSourceHealthMonitor monitor) {
            return () -> monitor.isReplicaHealthy()
                    ? Health.up().build()
                    : Health.down().build();
        }
    }

    // ── shared helper ─────────────────────────────────────────────────────────

    private static HikariDataSource buildHikari(
            String url, String username, String password,
            String poolPrefix, Environment environment) {

        HikariConfig config = Binder.get(environment)
                .bind(poolPrefix, Bindable.of(HikariConfig.class))
                .orElseGet(HikariConfig::new);
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        return new HikariDataSource(config);
    }
}
