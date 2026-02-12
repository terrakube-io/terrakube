package io.terrakube.api.plugin.scheduler.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.repository.ProviderImplementationRepository;
import io.terrakube.api.repository.ProviderRepository;
import io.terrakube.api.repository.ProviderVersionRepository;
import io.terrakube.api.rs.provider.Provider;
import io.terrakube.api.rs.provider.implementation.Implementation;
import io.terrakube.api.rs.provider.implementation.Version;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Quartz job that periodically checks the public Terraform Registry (registry.terraform.io)
 * for new versions of a provider and imports them into the Terrakube private registry.
 *
 * This mirrors the ModuleRefreshJob pattern but instead of using git ls-remote,
 * it queries the Terraform Registry v1 API for provider versions and platform downloads.
 */
@Slf4j
@Component
public class ProviderRefreshJob implements Job {

    private static final String TERRAFORM_REGISTRY_BASE_URL = "https://registry.terraform.io";

    /** Standard platforms to import for each version */
    private static final List<String[]> STANDARD_PLATFORMS = List.of(
            new String[]{"linux", "amd64"},
            new String[]{"linux", "arm64"},
            new String[]{"darwin", "amd64"},
            new String[]{"darwin", "arm64"},
            new String[]{"windows", "amd64"}
    );

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private ProviderVersionRepository providerVersionRepository;

    @Autowired
    private ProviderImplementationRepository providerImplementationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final WebClient webClient;

    public ProviderRefreshJob() {
        this.webClient = WebClient.builder()
                .baseUrl(TERRAFORM_REGISTRY_BASE_URL)
                .defaultHeaders(h -> {
                    h.setAccept(List.of(MediaType.APPLICATION_JSON));
                    h.add("User-Agent", "Terrakube/1.0 (https://terrakube.io)");
                })
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String providerId = context.getJobDetail().getJobDataMap().getString("providerId");
        log.info("ProviderRefreshJob running for providerId={}", providerId);

        try {
            UUID uuid = UUID.fromString(providerId);
            Optional<Provider> optProvider = providerRepository.findById(uuid);
            if (optProvider.isEmpty()) {
                log.warn("Provider {} not found, skipping refresh", providerId);
                return;
            }

            Provider provider = optProvider.get();
            refreshProvider(provider);
        } catch (Exception e) {
            log.error("Error refreshing provider {}: {}", providerId, e.getMessage(), e);
        }
    }

    /**
     * Fetches all versions from the public registry for this provider,
     * compares with existing versions in the DB, and imports any new ones.
     */
    private void refreshProvider(Provider provider) {
        // Parse namespace/name from the provider name (e.g. "goharbor/harbor")
        String providerName = provider.getName();
        if (!providerName.contains("/")) {
            log.warn("Provider name '{}' does not contain namespace/name format, skipping", providerName);
            return;
        }

        String[] parts = providerName.split("/", 2);
        String namespace = parts[0];
        String name = parts[1];

        log.info("Checking registry for new versions of {}/{}", namespace, name);

        // 1. Fetch all versions from the public registry
        List<RegistryVersion> registryVersions = fetchRegistryVersions(namespace, name);
        if (registryVersions.isEmpty()) {
            log.info("No versions found in registry for {}/{}", namespace, name);
            return;
        }

        // 2. Get existing versions from DB
        List<Version> existingVersions = providerVersionRepository.findAllByProviderId(provider.getId());
        Set<String> existingVersionNumbers = existingVersions.stream()
                .map(Version::getVersionNumber)
                .collect(Collectors.toSet());

        // 3. Find new versions
        List<RegistryVersion> newVersions = registryVersions.stream()
                .filter(rv -> !existingVersionNumbers.contains(rv.version))
                .collect(Collectors.toList());

        if (newVersions.isEmpty()) {
            log.info("Provider {}/{} is up to date ({} versions)", namespace, name, existingVersions.size());
            return;
        }

        log.info("Found {} new version(s) for {}/{}: {}", newVersions.size(), namespace, name,
                newVersions.stream().map(v -> v.version).collect(Collectors.joining(", ")));

        // 4. Import each new version
        for (RegistryVersion rv : newVersions) {
            try {
                importVersion(provider, namespace, name, rv);
            } catch (Exception e) {
                log.error("Failed to import version {} for {}/{}: {}",
                        rv.version, namespace, name, e.getMessage());
            }
        }
    }

    /**
     * Fetches the list of versions from the Terraform Registry v1 API.
     */
    private List<RegistryVersion> fetchRegistryVersions(String namespace, String name) {
        try {
            String response = webClient.get()
                    .uri("/v1/providers/{namespace}/{name}/versions", namespace, name)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofMillis(500))
                            .filter(t -> !(t instanceof WebClientResponseException.NotFound)))
                    .block(Duration.ofSeconds(30));

            if (response == null) return Collections.emptyList();

            JsonNode root = objectMapper.readTree(response);
            JsonNode versionsNode = root.path("versions");
            if (!versionsNode.isArray()) return Collections.emptyList();

            List<RegistryVersion> result = new ArrayList<>();
            for (JsonNode vNode : versionsNode) {
                String version = vNode.path("version").asText("");
                if (version.isEmpty()) continue;

                String protocols = "";
                JsonNode protocolsNode = vNode.path("protocols");
                if (protocolsNode.isArray()) {
                    List<String> protoList = new ArrayList<>();
                    protocolsNode.forEach(p -> protoList.add(p.asText()));
                    protocols = String.join(",", protoList);
                }

                List<String[]> platforms = new ArrayList<>();
                JsonNode platformsNode = vNode.path("platforms");
                if (platformsNode.isArray()) {
                    for (JsonNode pNode : platformsNode) {
                        platforms.add(new String[]{
                                pNode.path("os").asText(""),
                                pNode.path("arch").asText("")
                        });
                    }
                }

                result.add(new RegistryVersion(version, protocols, platforms));
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch versions from registry for {}/{}: {}", namespace, name, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Imports a single version: creates a Version record and Implementation records
     * for each available platform.
     */
    private void importVersion(Provider provider, String namespace, String name, RegistryVersion rv) {
        // Create the Version entity
        Version version = new Version();
        version.setVersionNumber(rv.version);
        version.setProtocols(rv.protocols.isEmpty() ? "5.0" : rv.protocols);
        version.setProvider(provider);
        version = providerVersionRepository.save(version);

        log.info("Created version {} for provider {}", rv.version, provider.getName());

        // Determine which platforms to import (intersection of available and standard)
        List<String[]> platformsToImport = STANDARD_PLATFORMS.stream()
                .filter(std -> rv.platforms.stream()
                        .anyMatch(p -> p[0].equals(std[0]) && p[1].equals(std[1])))
                .collect(Collectors.toList());

        // Fetch download info and create implementations for each platform
        for (String[] platform : platformsToImport) {
            try {
                importImplementation(provider, namespace, name, version, rv.version, platform[0], platform[1]);
            } catch (Exception e) {
                log.warn("Failed to import implementation {}/{} for {}/{} v{}: {}",
                        platform[0], platform[1], namespace, name, rv.version, e.getMessage());
            }
        }
    }

    /**
     * Fetches download info for a specific platform and creates an Implementation record.
     */
    private void importImplementation(Provider provider, String namespace, String name,
                                       Version version, String versionNumber,
                                       String os, String arch) {
        String response = webClient.get()
                .uri("/v1/providers/{namespace}/{name}/{version}/download/{os}/{arch}",
                        namespace, name, versionNumber, os, arch)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofMillis(500))
                        .filter(t -> !(t instanceof WebClientResponseException.NotFound)))
                .block(Duration.ofSeconds(30));

        if (response == null) return;

        try {
            JsonNode dl = objectMapper.readTree(response);

            Implementation impl = new Implementation();
            impl.setOs(truncate(dl.path("os").asText(""), 32));
            impl.setArch(truncate(dl.path("arch").asText(""), 32));
            impl.setFilename(truncate(dl.path("filename").asText(""), 512));
            impl.setDownloadUrl(truncate(dl.path("download_url").asText(""), 1024));
            impl.setShasumsUrl(truncate(dl.path("shasums_url").asText(""), 1024));
            impl.setShasumsSignatureUrl(truncate(dl.path("shasums_signature_url").asText(""), 1024));
            impl.setShasum(truncate(dl.path("shasum").asText(""), 1024));

            // GPG key info
            JsonNode gpgKeys = dl.path("signing_keys").path("gpg_public_keys");
            if (gpgKeys.isArray() && gpgKeys.size() > 0) {
                JsonNode key = gpgKeys.get(0);
                impl.setKeyId(truncate(key.path("key_id").asText(""), 32));
                impl.setAsciiArmor(key.path("ascii_armor").asText(""));
                impl.setTrustSignature(truncate(key.path("trust_signature").asText(""), 32));
                impl.setSource(truncate(key.path("source").asText("unknown"), 64));
                impl.setSourceUrl(truncate(key.path("source_url").asText("https://unknown"), 512));
            } else {
                impl.setKeyId("");
                impl.setAsciiArmor("");
                impl.setTrustSignature("");
                impl.setSource("unknown");
                impl.setSourceUrl("https://unknown");
            }

            impl.setVersion(version);
            providerImplementationRepository.save(impl);

            log.debug("Created implementation {}/{} for {} v{}", os, arch, provider.getName(), versionNumber);
        } catch (Exception e) {
            log.error("Failed to parse download info for {}/{} {}/{}: {}", namespace, name, os, arch, e.getMessage());
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /** Internal record for a version from the registry API */
    private static class RegistryVersion {
        final String version;
        final String protocols;
        final List<String[]> platforms;

        RegistryVersion(String version, String protocols, List<String[]> platforms) {
            this.version = version;
            this.protocols = protocols;
            this.platforms = platforms;
        }
    }
}
