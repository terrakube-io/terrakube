package io.terrakube.api.plugin.scheduler.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.repository.ProviderImplementationRepository;
import io.terrakube.api.repository.ProviderRepository;
import io.terrakube.api.repository.ProviderVersionRepository;
import io.terrakube.api.rs.provider.Provider;
import io.terrakube.api.rs.provider.SourceType;
import io.terrakube.api.rs.provider.implementation.Implementation;
import io.terrakube.api.rs.provider.implementation.Version;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Quartz job that checks a provider registry (or repository release page) for new versions of a
 * provider and imports them into the Terrakube private registry.
 *
 * <p>Two source types are supported (see {@link SourceType}):
 * <ul>
 *     <li>{@code TERRAFORM_REGISTRY}: any registry implementing the Terraform provider registry
 *     protocol. The host comes from {@link Provider#getRegistryHost()} (defaulting to the public
 *     registry.terraform.io) and is resolved through service discovery
 *     (/.well-known/terraform.json). Works with GitLab, Artifactory/JFrog, TFE and self hosted
 *     registries.</li>
 *     <li>{@code REPOSITORY}: a base URL hosting goreleaser style release assets
 *     ({@code terraform-provider-NAME_VERSION_OS_ARCH.zip} + {@code SHA256SUMS} +
 *     {@code SHA256SUMS.sig}). Versions to import are listed in
 *     {@link Provider#getRepositoryVersions()} and platforms/shasums are discovered from the
 *     SHA256SUMS file.</li>
 * </ul>
 *
 * <p>Private sources can be authenticated with a bearer token ({@link Provider#getRegistryToken()}).
 */
@Slf4j
@Component
public class ProviderRefreshJob implements Job {

    static final String PUBLIC_REGISTRY_BASE_URL = "https://registry.terraform.io";
    static final String DEFAULT_PROVIDERS_PATH = "/v1/providers/";

    /** Standard platforms to import for each version (TERRAFORM_REGISTRY source). */
    private static final List<String[]> STANDARD_PLATFORMS = List.of(
            new String[]{"linux", "amd64"},
            new String[]{"linux", "arm64"},
            new String[]{"darwin", "amd64"},
            new String[]{"darwin", "arm64"},
            new String[]{"windows", "amd64"}
    );

    /** Matches a goreleaser asset: terraform-provider-NAME_VERSION_OS_ARCH.zip */
    private static final Pattern ASSET_PATTERN =
            Pattern.compile("^terraform-provider-.+_([^_]+)_([^_]+)\\.zip$");

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
                .defaultHeaders(h -> h.add("User-Agent", "Terrakube/1.0 (https://terrakube.io)"))
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
            refreshProvider(optProvider.get());
        } catch (Exception e) {
            log.error("Error refreshing provider {}: {}", providerId, e.getMessage(), e);
        }
    }

    private void refreshProvider(Provider provider) {
        if (!provider.isImported()) {
            log.warn("Provider '{}' is not an imported provider, skipping refresh", provider.getName());
            return;
        }

        SourceType sourceType = provider.getSourceType() == null ? SourceType.TERRAFORM_REGISTRY : provider.getSourceType();
        switch (sourceType) {
            case REPOSITORY:
                refreshFromRepository(provider);
                break;
            case TERRAFORM_REGISTRY:
            default:
                refreshFromRegistry(provider);
                break;
        }
    }

    // ---------------------------------------------------------------------
    // TERRAFORM_REGISTRY source
    // ---------------------------------------------------------------------

    private void refreshFromRegistry(Provider provider) {
        if (provider.getRegistryNamespace() == null || provider.getRegistryNamespace().isEmpty()) {
            log.warn("Provider '{}' has no registryNamespace, skipping refresh", provider.getName());
            return;
        }

        String namespace = provider.getRegistryNamespace();
        String name = provider.getName();
        String providersBase = resolveProvidersBase(provider);

        log.info("Checking registry {} for new versions of {}/{}", providersBase, namespace, name);

        List<RegistryVersion> registryVersions = fetchRegistryVersions(provider, providersBase, namespace, name);
        if (registryVersions.isEmpty()) {
            log.info("No versions found in registry for {}/{}", namespace, name);
            return;
        }

        Set<String> existing = existingVersionNumbers(provider);
        List<RegistryVersion> newVersions = registryVersions.stream()
                .filter(rv -> !existing.contains(rv.version))
                .collect(Collectors.toList());

        if (newVersions.isEmpty()) {
            log.info("Provider {}/{} is up to date ({} versions)", namespace, name, existing.size());
            return;
        }

        log.info("Found {} new version(s) for {}/{}: {}", newVersions.size(), namespace, name,
                newVersions.stream().map(v -> v.version).collect(Collectors.joining(", ")));

        for (RegistryVersion rv : newVersions) {
            try {
                importRegistryVersion(provider, providersBase, namespace, name, rv);
            } catch (Exception e) {
                log.error("Failed to import version {} for {}/{}: {}", rv.version, namespace, name, e.getMessage());
            }
        }
    }

    /**
     * Resolves the absolute providers protocol base URL for a provider, performing service
     * discovery against the configured host when one is set.
     */
    String resolveProvidersBase(Provider provider) {
        String host = provider.getRegistryHost();
        if (host == null || host.isBlank()) {
            return PUBLIC_REGISTRY_BASE_URL + DEFAULT_PROVIDERS_PATH;
        }

        String hostBaseUrl = host.startsWith("http") ? stripTrailingSlash(host) : "https://" + stripTrailingSlash(host);
        try {
            String body = webClient.get()
                    .uri(URI.create(hostBaseUrl + "/.well-known/terraform.json"))
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> applyAuth(h, provider))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
            String resolved = providersBaseFromDiscovery(body, hostBaseUrl);
            if (resolved != null) {
                return resolved;
            }
        } catch (Exception e) {
            log.warn("Service discovery failed for host {} ({}), falling back to {}",
                    hostBaseUrl, e.getMessage(), DEFAULT_PROVIDERS_PATH);
        }
        return hostBaseUrl + DEFAULT_PROVIDERS_PATH;
    }

    /**
     * Parses the providers.v1 entry from a /.well-known/terraform.json body and resolves it to an
     * absolute URL ending with a slash. Returns null when the body cannot be parsed.
     */
    String providersBaseFromDiscovery(String discoveryBody, String hostBaseUrl) {
        if (discoveryBody == null || discoveryBody.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(discoveryBody);
            String providersV1 = root.path("providers.v1").asText("");
            if (providersV1.isEmpty()) return null;
            String absolute = providersV1.startsWith("http")
                    ? providersV1
                    : hostBaseUrl + (providersV1.startsWith("/") ? providersV1 : "/" + providersV1);
            return absolute.endsWith("/") ? absolute : absolute + "/";
        } catch (Exception e) {
            log.warn("Failed to parse service discovery document: {}", e.getMessage());
            return null;
        }
    }

    private List<RegistryVersion> fetchRegistryVersions(Provider provider, String providersBase,
                                                        String namespace, String name) {
        try {
            String response = webClient.get()
                    .uri(URI.create(providersBase + namespace + "/" + name + "/versions"))
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> applyAuth(h, provider))
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofMillis(500))
                            .filter(t -> !(t instanceof WebClientResponseException.NotFound)))
                    .block(Duration.ofSeconds(30));

            return parseRegistryVersions(response);
        } catch (Exception e) {
            log.error("Failed to fetch versions from registry for {}/{}: {}", namespace, name, e.getMessage());
            return Collections.emptyList();
        }
    }

    List<RegistryVersion> parseRegistryVersions(String response) {
        if (response == null) return Collections.emptyList();
        try {
            JsonNode versionsNode = objectMapper.readTree(response).path("versions");
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
                        platforms.add(new String[]{pNode.path("os").asText(""), pNode.path("arch").asText("")});
                    }
                }
                result.add(new RegistryVersion(version, protocols, platforms));
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse registry versions: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void importRegistryVersion(Provider provider, String providersBase, String namespace,
                                       String name, RegistryVersion rv) {
        Version version = saveVersion(provider, rv.version, rv.protocols.isEmpty() ? "5.0" : rv.protocols);
        log.info("Created version {} for provider {}", rv.version, provider.getName());

        List<String[]> platformsToImport = STANDARD_PLATFORMS.stream()
                .filter(std -> rv.platforms.stream().anyMatch(p -> p[0].equals(std[0]) && p[1].equals(std[1])))
                .collect(Collectors.toList());

        for (String[] platform : platformsToImport) {
            try {
                importRegistryImplementation(provider, providersBase, namespace, name, version, rv.version, platform[0], platform[1]);
            } catch (Exception e) {
                log.warn("Failed to import implementation {}/{} for {}/{} v{}: {}",
                        platform[0], platform[1], namespace, name, rv.version, e.getMessage());
            }
        }
    }

    private void importRegistryImplementation(Provider provider, String providersBase, String namespace,
                                              String name, Version version, String versionNumber,
                                              String os, String arch) {
        String response = webClient.get()
                .uri(URI.create(providersBase + namespace + "/" + name + "/" + versionNumber + "/download/" + os + "/" + arch))
                .accept(MediaType.APPLICATION_JSON)
                .headers(h -> applyAuth(h, provider))
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

            JsonNode gpgKeys = dl.path("signing_keys").path("gpg_public_keys");
            if (gpgKeys.isArray() && gpgKeys.size() > 0) {
                JsonNode key = gpgKeys.get(0);
                impl.setKeyId(truncate(key.path("key_id").asText(""), 32));
                impl.setAsciiArmor(key.path("ascii_armor").asText(""));
                impl.setTrustSignature(truncate(key.path("trust_signature").asText(""), 32));
                impl.setSource(truncate(key.path("source").asText("unknown"), 64));
                impl.setSourceUrl(truncate(key.path("source_url").asText("https://unknown"), 512));
            } else {
                applyEmptyGpg(impl);
            }

            impl.setVersion(version);
            providerImplementationRepository.save(impl);
            log.debug("Created implementation {}/{} for {} v{}", os, arch, provider.getName(), versionNumber);
        } catch (Exception e) {
            log.error("Failed to parse download info for {}/{} {}/{}: {}", namespace, name, os, arch, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // REPOSITORY source
    // ---------------------------------------------------------------------

    private void refreshFromRepository(Provider provider) {
        if (provider.getRepositoryUrl() == null || provider.getRepositoryUrl().isBlank()) {
            log.warn("Provider '{}' has no repositoryUrl, skipping refresh", provider.getName());
            return;
        }

        List<String> requested = parseVersionList(provider.getRepositoryVersions());
        if (requested.isEmpty()) {
            log.warn("Provider '{}' has no repositoryVersions configured, nothing to import", provider.getName());
            return;
        }

        Set<String> existing = existingVersionNumbers(provider);
        List<String> newVersions = requested.stream().filter(v -> !existing.contains(v)).collect(Collectors.toList());
        if (newVersions.isEmpty()) {
            log.info("Provider {} is up to date ({} versions)", provider.getName(), existing.size());
            return;
        }

        for (String versionNumber : newVersions) {
            try {
                importRepositoryVersion(provider, versionNumber);
            } catch (Exception e) {
                log.error("Failed to import version {} for {}: {}", versionNumber, provider.getName(), e.getMessage());
            }
        }
    }

    private void importRepositoryVersion(Provider provider, String versionNumber) {
        String name = provider.getName();
        String shasumsFile = "terraform-provider-" + name + "_" + versionNumber + "_SHA256SUMS";
        String shasumsUrl = buildAssetUrl(provider.getRepositoryUrl(), versionNumber, shasumsFile);
        String shasumsSignatureUrl = buildAssetUrl(provider.getRepositoryUrl(), versionNumber, shasumsFile + ".sig");

        String shasumsContent = webClient.get()
                .uri(URI.create(shasumsUrl))
                .headers(h -> applyAuth(h, provider))
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofMillis(500))
                        .filter(t -> !(t instanceof WebClientResponseException.NotFound)))
                .block(Duration.ofSeconds(30));

        Map<String, String> shasums = parseShaSums(shasumsContent);
        if (shasums.isEmpty()) {
            log.warn("No SHA256SUMS entries found at {} for provider {}", shasumsUrl, name);
            return;
        }

        Version version = saveVersion(provider, versionNumber, "5.0");
        log.info("Created version {} for provider {} (repository source)", versionNumber, name);

        for (Map.Entry<String, String> entry : shasums.entrySet()) {
            String filename = entry.getKey();
            String[] platform = platformFromFilename(filename);
            if (platform == null) continue;

            Implementation impl = new Implementation();
            impl.setOs(truncate(platform[0], 32));
            impl.setArch(truncate(platform[1], 32));
            impl.setFilename(truncate(filename, 512));
            impl.setDownloadUrl(truncate(buildAssetUrl(provider.getRepositoryUrl(), versionNumber, filename), 1024));
            impl.setShasumsUrl(truncate(shasumsUrl, 1024));
            impl.setShasumsSignatureUrl(truncate(shasumsSignatureUrl, 1024));
            impl.setShasum(truncate(entry.getValue(), 1024));

            if (provider.getGpgAsciiArmor() != null && !provider.getGpgAsciiArmor().isBlank()) {
                impl.setKeyId(truncate(provider.getGpgKeyId() == null ? "" : provider.getGpgKeyId(), 32));
                impl.setAsciiArmor(provider.getGpgAsciiArmor());
                impl.setTrustSignature("");
                impl.setSource(truncate(name, 64));
                impl.setSourceUrl(truncate(provider.getRepositoryUrl(), 512));
            } else {
                applyEmptyGpg(impl);
            }

            impl.setVersion(version);
            providerImplementationRepository.save(impl);
            log.debug("Created implementation {}/{} for {} v{} (repository source)",
                    platform[0], platform[1], name, versionNumber);
        }
    }

    /**
     * Builds a release asset URL. {@code repositoryUrl} may contain a {@code {version}} placeholder
     * (useful for release pages where the tag is part of the path); otherwise all assets are assumed
     * to live directly under the base URL.
     */
    static String buildAssetUrl(String repositoryUrl, String version, String filename) {
        String resolved = repositoryUrl.replace("{version}", version);
        return stripTrailingSlash(resolved) + "/" + filename;
    }

    /**
     * Parses the contents of a SHA256SUMS file into a map of filename to hex sha256.
     * Each line looks like: {@code <sha256>  terraform-provider-foo_1.2.3_linux_amd64.zip}.
     */
    static Map<String, String> parseShaSums(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        if (content == null) return result;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 2);
            if (parts.length != 2) continue;
            String sha = parts[0].trim();
            // a leading '*' marks binary mode in some sha256sum outputs
            String filename = parts[1].trim();
            if (filename.startsWith("*")) filename = filename.substring(1);
            if (!sha.isEmpty() && !filename.isEmpty()) {
                result.put(filename, sha);
            }
        }
        return result;
    }

    /**
     * Extracts the os and arch from a goreleaser provider asset filename.
     * Returns {@code null} when the filename is not a provider zip (e.g. the manifest or signature).
     */
    static String[] platformFromFilename(String filename) {
        if (filename == null) return null;
        Matcher m = ASSET_PATTERN.matcher(filename);
        if (!m.matches()) return null;
        return new String[]{m.group(1), m.group(2)};
    }

    static List<String> parseVersionList(String versions) {
        if (versions == null || versions.isBlank()) return Collections.emptyList();
        return Arrays.stream(versions.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private Set<String> existingVersionNumbers(Provider provider) {
        return providerVersionRepository.findAllByProviderId(provider.getId()).stream()
                .map(Version::getVersionNumber)
                .collect(Collectors.toSet());
    }

    private Version saveVersion(Provider provider, String versionNumber, String protocols) {
        Version version = new Version();
        version.setVersionNumber(versionNumber);
        version.setProtocols(protocols);
        version.setProvider(provider);
        return providerVersionRepository.save(version);
    }

    private void applyAuth(HttpHeaders headers, Provider provider) {
        if (provider.getRegistryToken() != null && !provider.getRegistryToken().isBlank()) {
            headers.setBearerAuth(provider.getRegistryToken());
        }
    }

    private static void applyEmptyGpg(Implementation impl) {
        impl.setKeyId("");
        impl.setAsciiArmor("");
        impl.setTrustSignature("");
        impl.setSource("unknown");
        impl.setSourceUrl("https://unknown");
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /** Internal record for a version from the registry API */
    static class RegistryVersion {
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
