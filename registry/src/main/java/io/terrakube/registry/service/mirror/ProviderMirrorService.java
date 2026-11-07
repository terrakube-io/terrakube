package io.terrakube.registry.service.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.plugin.storage.StorageUnavailableException;
import io.terrakube.registry.service.module.ModuleDownloadTicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Provider network mirror. Files live under {@code mirror/} in the registry storage, in the layout
 * {@code terraform providers mirror} writes. Storage is shared by all organizations. With pull-through on, a
 * provider whose hostname is an allowlisted upstream is listed from that upstream and its archives are fetched,
 * checked against the upstream SHA-256 and stored on first use.
 */
@Slf4j
@Service
public class ProviderMirrorService {

    private static final String PREFIX = "mirror/";
    private static final Pattern HOSTNAME = Pattern.compile("[a-z0-9][a-z0-9.-]{0,252}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}");
    private static final Pattern PLATFORM = Pattern.compile("[a-z0-9]+_[a-z0-9]+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final long METADATA_CACHE_MAX_CHARS = 64L * 1024 * 1024;

    private final StorageService storageService;
    private final MirrorDownloadGuard guard;
    private final MirrorProperties properties;
    private final ModuleDownloadTicketService ticketService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Upstream metadata documents by URL: discovery, version lists, download details of one platform, SHA256SUMS. */
    private final Cache<URI, String> metadata;
    private final Map<String, CompletableFuture<Boolean>> inflight = new ConcurrentHashMap<>();
    /** Bounds the archive downloads (temp files, upstream bandwidth) one replica runs at once. */
    private final Semaphore fetchPermits;
    long fetchPermitWaitMillis = 10_000;

    public ProviderMirrorService(StorageService storageService, MirrorDownloadGuard guard, MirrorProperties properties,
            ModuleDownloadTicketService ticketService) {
        this.storageService = storageService;
        this.guard = guard;
        this.properties = properties;
        this.ticketService = ticketService;
        this.metadata = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.getMetadataCacheTtlSeconds()))
                // Weighed by characters: one document may be up to 16 MB, so a count bound could hold gigabytes.
                .maximumWeight(METADATA_CACHE_MAX_CHARS)
                .weigher((URI uri, String body) -> body.length())
                .build();
        this.fetchPermits = new Semaphore(properties.getMaxConcurrentFetches());
    }

    /** The isValid checks run before any part is used in a storage key or upstream URL. */
    public static boolean isValidProvider(String hostname, String namespace, String type) {
        return HOSTNAME.matcher(hostname).matches() && !hostname.contains("..")
                && NAME.matcher(namespace).matches() && NAME.matcher(type).matches();
    }

    public static boolean isValidVersion(String version) {
        return VERSION.matcher(version).matches() && !version.contains("..");
    }

    public static boolean isValidPlatform(String platform) {
        return PLATFORM.matcher(platform).matches();
    }

    /** Body of index.json. */
    public Optional<String> index(String hostname, String namespace, String type) {
        Optional<URI> upstream = upstreamFor(hostname);
        if (upstream.isEmpty()) {
            return readString(directory(hostname, namespace, type) + "index.json");
        }
        return upstreamVersions(providersBase(upstream.get()), namespace, type).map(versions -> {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode list = body.putObject("versions");
            for (JsonNode version : versions.path("versions")) {
                list.putObject(version.path("version").asText());
            }
            return body.toString();
        });
    }

    /**
     * Body of {version}.json. Archive URLs always point at this registry with a download ticket, relative to the
     * version document as the protocol allows; the url field of a stored file is never used.
     */
    public Optional<String> version(String organization, String hostname, String namespace, String type, String version) {
        Optional<URI> upstream = upstreamFor(hostname);
        Optional<JsonNode> archives = upstream.isEmpty()
                ? readStoredJson(directory(hostname, namespace, type) + version + ".json").map(node -> node.path("archives"))
                : upstreamArchives(providersBase(upstream.get()), namespace, type, version);
        return archives.map(found -> {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode list = body.putObject("archives");
            for (Map.Entry<String, JsonNode> entry : found.properties()) {
                String platform = entry.getKey();
                if (!isValidPlatform(platform)) {
                    continue;
                }
                ObjectNode archive = list.putObject(platform);
                archive.put("url", version + "/" + platform + ".zip?ticket="
                        + ticketService.generateMirrorTicket(organization, hostname, namespace, type, version, platform));
                JsonNode hashes = entry.getValue().get("hashes");
                if (hashes != null && hashes.isArray()) {
                    archive.set("hashes", hashes);
                }
            }
            return body.toString();
        });
    }

    /** Storage key of the archive, fetching it first when pull-through applies. Empty when there is none. */
    public Optional<String> archiveKey(String hostname, String namespace, String type, String version, String platform) {
        String key = directory(hostname, namespace, type) + archiveFileName(type, version, platform);
        if (storageService.objectExists(key)) {
            return Optional.of(key);
        }
        Optional<URI> upstream = upstreamFor(hostname);
        if (upstream.isEmpty()) {
            return Optional.empty();
        }
        boolean stored = fetchOnce(key, () -> withFetchPermit(key,
                () -> fetchAndStore(upstream.get(), key, namespace, type, version, platform)));
        return stored ? Optional.of(key) : Optional.empty();
    }

    private Optional<URI> upstreamFor(String hostname) {
        if (!properties.isPullThroughEnabled()) {
            return Optional.empty();
        }
        return properties.getUpstreams().stream().filter(upstream -> hostname.equalsIgnoreCase(upstream.getHost())).findFirst();
    }

    private static String directory(String hostname, String namespace, String type) {
        return PREFIX + hostname + "/" + namespace + "/" + type + "/";
    }

    private URI providersBase(URI upstream) {
        JsonNode discovery = cachedJson(upstream.resolve("/.well-known/terraform.json"))
                .orElseThrow(() -> new MirrorUpstreamException("No service discovery on " + upstream.getHost()));
        String providers = discovery.path("providers.v1").asText("");
        if (providers.isBlank()) {
            throw new MirrorUpstreamException(upstream.getHost() + " has no providers.v1 service");
        }
        return upstream.resolve(providers.endsWith("/") ? providers : providers + "/");
    }

    private Optional<JsonNode> upstreamVersions(URI base, String namespace, String type) {
        return cachedJson(base.resolve(namespace + "/" + type + "/versions"));
    }

    private Optional<JsonNode> upstreamArchives(URI base, String namespace, String type, String version) {
        Optional<JsonNode> entry = upstreamVersions(base, namespace, type).flatMap(versions -> {
            for (JsonNode candidate : versions.path("versions")) {
                if (version.equals(candidate.path("version").asText())) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        });
        return entry.map(found -> {
            JsonNode platforms = found.path("platforms");
            Map<String, String> sums = platforms.isEmpty() ? Map.of() : shasums(base, namespace, type, version, platforms.get(0));
            ObjectNode archives = objectMapper.createObjectNode();
            for (JsonNode platform : platforms) {
                String name = platform.path("os").asText() + "_" + platform.path("arch").asText();
                ObjectNode archive = archives.putObject(name);
                String sum = sums.get(archiveFileName(type, version, name));
                if (sum != null) {
                    archive.putArray("hashes").add("zh:" + sum.toLowerCase());
                }
            }
            return archives;
        });
    }

    /** File name to SHA-256 from the release SHA256SUMS, found through the download details of any one platform. */
    private Map<String, String> shasums(URI base, String namespace, String type, String version, JsonNode platform) {
        URI download = downloadUri(base, namespace, type, version, platform.path("os").asText(), platform.path("arch").asText());
        return cachedJson(download).map(info -> shasums(download, info)).orElse(Map.of());
    }

    /** File name to SHA-256 from the SHA256SUMS that the download details {@code info} point at. */
    private Map<String, String> shasums(URI download, JsonNode info) {
        String url = info.path("shasums_url").asText("");
        if (url.isBlank()) {
            return Map.of();
        }
        Map<String, String> sums = new HashMap<>();
        for (String line : cachedString(download.resolve(url)).orElse("").split("\n")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && SHA256.matcher(parts[0]).matches()) {
                sums.put(parts[1].startsWith("*") ? parts[1].substring(1) : parts[1], parts[0]);
            }
        }
        return sums;
    }

    private static String archiveFileName(String type, String version, String platform) {
        return "terraform-provider-" + type + "_" + version + "_" + platform + ".zip";
    }

    private static URI downloadUri(URI base, String namespace, String type, String version, String os, String arch) {
        return base.resolve(namespace + "/" + type + "/" + version + "/download/" + os + "/" + arch);
    }

    /** Runs the fetch once per key on this replica; concurrent callers wait for it. Across replicas the write is idempotent. */
    private boolean fetchOnce(String key, Supplier<Boolean> fetch) {
        CompletableFuture<Boolean> mine = new CompletableFuture<>();
        CompletableFuture<Boolean> running = inflight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException cause ? cause : e;
            }
        }
        try {
            boolean stored = fetch.get();
            mine.complete(stored);
            return stored;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key, mine);
        }
    }

    private boolean withFetchPermit(String key, Supplier<Boolean> fetch) {
        try {
            if (!fetchPermits.tryAcquire(fetchPermitWaitMillis, TimeUnit.MILLISECONDS)) {
                throw new MirrorBusyException("All " + properties.getMaxConcurrentFetches()
                        + " pull-through fetch slots are busy, not fetching " + key);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MirrorBusyException("Interrupted waiting for a pull-through fetch slot for " + key);
        }
        try {
            return fetch.get();
        } finally {
            fetchPermits.release();
        }
    }

    private boolean fetchAndStore(URI upstream, String key, String namespace, String type, String version, String platform) {
        if (storageService.objectExists(key)) {
            return true;
        }
        String[] osArch = platform.split("_", 2);
        URI download = downloadUri(providersBase(upstream), namespace, type, version, osArch[0], osArch[1]);
        Optional<JsonNode> info = guard.getString(download).map(this::parse);
        if (info.isEmpty()) {
            return false;
        }
        String url = info.get().path("download_url").asText("");
        String shasum = info.get().path("shasum").asText("");
        if (url.isBlank() || !SHA256.matcher(shasum).matches()) {
            throw new MirrorUpstreamException("Incomplete download details for " + key + " on " + upstream.getHost());
        }
        // The zh: hashes served to clients come from SHA256SUMS under this same file name, so the archive must
        // match that entry as well as the shasum next to download_url. Without a SHA256SUMS there are no zh:
        // hashes to disagree with.
        Map<String, String> sums = shasums(download, info.get());
        if (!sums.isEmpty() && !shasum.equalsIgnoreCase(sums.get(archiveFileName(type, version, platform)))) {
            throw new MirrorUpstreamException("SHA256SUMS has no matching entry for " + key + " on " + upstream.getHost());
        }
        Path temp = null;
        try {
            temp = Files.createTempFile("mirror", ".zip");
            guard.download(download.resolve(url), temp, properties.getMaxArchiveSizeMb() * 1024 * 1024);
            String actual = sha256(temp);
            if (!actual.equalsIgnoreCase(shasum)) {
                throw new MirrorUpstreamException("Checksum mismatch for " + key + ": expected " + shasum + ", got " + actual);
            }
            storageService.writeObject(key, temp, "application/zip");
            log.info("Stored mirrored provider archive {}", key);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Could not delete temporary file {}", temp);
                }
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Optional<JsonNode> cachedJson(URI uri) {
        return cachedString(uri).map(this::parse);
    }

    private Optional<String> cachedString(URI uri) {
        String cached = metadata.getIfPresent(uri);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<String> body = guard.getString(uri);
        body.ifPresent(value -> metadata.put(uri, value));
        return body;
    }

    private Optional<String> readString(String key) {
        return storageService.readObject(key).map(in -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new StorageUnavailableException("Failed to read " + key, e);
            }
        });
    }

    // An operator-uploaded file, so a broken one is our error (500), not the upstream's (502).
    private Optional<JsonNode> readStoredJson(String key) {
        return readString(key).map(json -> {
            try {
                return objectMapper.readTree(json);
            } catch (JsonProcessingException e) {
                log.error("Stored provider mirror file {} is not valid JSON: {}", key, e.getOriginalMessage());
                throw new IllegalStateException("Stored provider mirror file " + key + " is not valid JSON", e);
            }
        });
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new MirrorUpstreamException("Invalid provider mirror JSON document", e);
        }
    }
}
