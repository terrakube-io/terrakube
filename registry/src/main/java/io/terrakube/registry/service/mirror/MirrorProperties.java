package io.terrakube.registry.service.mirror;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.registry.mirror")
public class MirrorProperties {

    /** Fetch missing provider archives from an allowlisted upstream registry and keep them in storage. */
    private boolean pullThroughEnabled = false;

    /** Upstream registries pull-through may use, matched on the exact provider hostname. */
    private List<URI> upstreams = new ArrayList<>(List.of(
            URI.create("https://registry.terraform.io"), URI.create("https://registry.opentofu.org")));

    private long metadataCacheTtlSeconds = 600;
    private long maxArchiveSizeMb = 1024;
    private long downloadTimeoutSeconds = 300;
    /** Pull-through fetches running at once on one replica; more wait briefly, then get a 503. */
    private int maxConcurrentFetches = 4;

    /** Allows http upstreams and downloads. For tests against a local stub only. */
    private boolean allowInsecureUpstreams = false;

    @PostConstruct
    void validate() {
        if (metadataCacheTtlSeconds <= 0 || maxArchiveSizeMb <= 0 || downloadTimeoutSeconds <= 0
                || maxConcurrentFetches <= 0) {
            throw new IllegalStateException("io.terrakube.registry.mirror: metadata-cache-ttl-seconds, "
                    + "max-archive-size-mb, download-timeout-seconds and max-concurrent-fetches must be at least 1");
        }
        for (URI upstream : upstreams) {
            boolean https = "https".equalsIgnoreCase(upstream.getScheme());
            boolean http = "http".equalsIgnoreCase(upstream.getScheme());
            if (upstream.getHost() == null || !(https || (http && allowInsecureUpstreams))) {
                throw new IllegalStateException("io.terrakube.registry.mirror.upstreams must be https URLs, got "
                        + upstream);
            }
        }
    }
}
