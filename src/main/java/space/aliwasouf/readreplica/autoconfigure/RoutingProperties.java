package space.aliwasouf.readreplica.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.datasource.routing")
public record RoutingProperties(MasterEndpoint master, ReplicaEndpoint replica) {

    /** Probe interval of {@code 0} disables health monitoring for this datasource. */
    static final int DEFAULT_PROBE_INTERVAL_SECONDS = 15;

    public record MasterEndpoint(
            String url,
            String username,
            String password,
            Integer probeIntervalSeconds) {

        public int effectiveProbeIntervalSeconds() {
            return probeIntervalSeconds != null ? probeIntervalSeconds : DEFAULT_PROBE_INTERVAL_SECONDS;
        }
    }

    public record ReplicaEndpoint(
            String url,
            String username,
            String password,
            Integer retryIntervalSeconds) {

        public int effectiveRetryIntervalSeconds() {
            return retryIntervalSeconds != null ? retryIntervalSeconds : DEFAULT_PROBE_INTERVAL_SECONDS;
        }
    }
}
