package space.aliwasouf.readreplica.autoconfigure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
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
        return buildHikari(properties.master(), MASTER_POOL_PREFIX, environment);
    }

    @Bean(name = "replicaDataSource")
    @ConditionalOnMissingBean(name = "replicaDataSource")
    public DataSource replicaDataSource(RoutingProperties properties, Environment environment) {
        return buildHikari(properties.replica(), REPLICA_POOL_PREFIX, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyAspect readOnlyAspect(ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        return new ReadOnlyAspect(entityManagerFactory);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "dataSource")
    public DataSource dataSource(DataSource masterDataSource, DataSource replicaDataSource) {
        RoutingDataSource routing = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(RoutingTarget.MASTER, masterDataSource);
        targets.put(RoutingTarget.REPLICA, replicaDataSource);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(masterDataSource);
        routing.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routing);
    }

    private static HikariDataSource buildHikari(
            RoutingProperties.Endpoint endpoint,
            String poolPrefix,
            Environment environment) {

        HikariConfig config = Binder.get(environment)
                .bind(poolPrefix, Bindable.of(HikariConfig.class))
                .orElseGet(HikariConfig::new);
        config.setJdbcUrl(endpoint.url());
        config.setUsername(endpoint.username());
        config.setPassword(endpoint.password());
        return new HikariDataSource(config);
    }
}
