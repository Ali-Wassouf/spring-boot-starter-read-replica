package space.aliwasouf.readreplica.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.datasource.routing")
public record RoutingProperties(Endpoint master, Endpoint replica) {

    public record Endpoint(String url, String username, String password) {
    }
}
