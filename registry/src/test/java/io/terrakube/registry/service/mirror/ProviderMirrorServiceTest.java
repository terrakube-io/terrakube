package io.terrakube.registry.service.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.module.ModuleDownloadTicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderMirrorServiceTest {

    private static final String ARCHIVE_PATH = "/files/terraform-provider-random_3.6.0_linux_amd64.zip";
    private static final String KEY = "mirror/localhost/hashicorp/random/terraform-provider-random_3.6.0_linux_amd64.zip";
    private static final byte[] ARCHIVE = "zip-bytes-of-random".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "dGVzdC1pbnRlcm5hbC1qd3Qtc2VjcmV0LWtleS0zMi1ieXRlcw";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModuleDownloadTicketService ticketService = new ModuleDownloadTicketService(SECRET, 300);
    private WireMockServer upstream;
    private StorageService storage;
    private MirrorProperties properties;
    private String archiveSha;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("localhost"));
        upstream.start();
        storage = mock(StorageService.class);
        properties = new MirrorProperties();
        properties.setPullThroughEnabled(true);
        properties.setAllowInsecureUpstreams(true);
        properties.setUpstreams(List.of(URI.create("http://localhost:" + upstream.port())));
        archiveSha = sha256(ARCHIVE);

        upstream.stubFor(get("/.well-known/terraform.json").willReturn(aResponse()
                .withBody("{\"providers.v1\":\"/v1/providers/\"}")));
        upstream.stubFor(get("/v1/providers/hashicorp/random/versions").willReturn(aResponse().withBody("""
                {"versions":[
                  {"version":"3.6.0","protocols":["5.0"],"platforms":[{"os":"linux","arch":"amd64"},{"os":"darwin","arch":"arm64"}]},
                  {"version":"3.5.1","protocols":["5.0"],"platforms":[{"os":"linux","arch":"amd64"}]}
                ]}""")));
        stubDownloadDetails(archiveSha);
        upstream.stubFor(get("/files/SHA256SUMS").willReturn(aResponse().withBody(
                archiveSha + "  terraform-provider-random_3.6.0_linux_amd64.zip\n"
                        + "b".repeat(64) + "  terraform-provider-random_3.6.0_darwin_arm64.zip\n")));
        upstream.stubFor(get(ARCHIVE_PATH).willReturn(aResponse().withBody(ARCHIVE)));
    }

    @AfterEach
    void tearDown() {
        upstream.stop();
    }

    private void stubDownloadDetails(String shasum) {
        upstream.stubFor(get("/v1/providers/hashicorp/random/3.6.0/download/linux/amd64").willReturn(aResponse().withBody("""
                {"os":"linux","arch":"amd64","filename":"terraform-provider-random_3.6.0_linux_amd64.zip",
                 "download_url":"%s","shasums_url":"/files/SHA256SUMS","shasum":"%s"}""".formatted(ARCHIVE_PATH, shasum))));
    }

    private ProviderMirrorService service() {
        MirrorDownloadGuard guard = new MirrorDownloadGuard(MirrorDownloadGuardTest.localhostIsPublic(), true, Duration.ofSeconds(10));
        return new ProviderMirrorService(storage, guard, properties, ticketService);
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void indexComesFromUpstreamVersions() throws Exception {
        JsonNode index = objectMapper.readTree(service().index("localhost", "hashicorp", "random").orElseThrow());

        assertThat(index.path("versions").properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("3.6.0", "3.5.1");
        assertThat(index.path("versions").path("3.6.0").isObject()).isTrue();
        verify(storage, never()).readObject(anyString());
    }

    @Test
    void versionJsonHasZhHashesFromShasums() throws Exception {
        JsonNode version = objectMapper.readTree(service().version("my-org", "localhost", "hashicorp", "random", "3.6.0").orElseThrow());

        JsonNode linux = version.path("archives").path("linux_amd64");
        assertThat(linux.path("hashes").get(0).asText()).isEqualTo("zh:" + archiveSha);
        assertThat(version.path("archives").path("darwin_arm64").path("hashes").get(0).asText()).isEqualTo("zh:" + "b".repeat(64));
        String url = linux.path("url").asText();
        assertThat(url).startsWith("3.6.0/linux_amd64.zip?ticket=");
        assertThat(ticketService.validateMirrorTicket(url.substring(url.indexOf("ticket=") + 7),
                "my-org", "localhost", "hashicorp", "random", "3.6.0", "linux_amd64")).isTrue();
    }

    @Test
    void unknownVersionOrProviderIsEmpty() {
        assertThat(service().version("my-org", "localhost", "hashicorp", "random", "9.9.9")).isEmpty();
        assertThat(service().index("localhost", "hashicorp", "nope")).isEmpty();
    }

    @Test
    void archiveMissFetchesVerifiesAndStores() {
        AtomicReference<byte[]> written = new AtomicReference<>();
        doAnswer(invocation -> {
            written.set(Files.readAllBytes(invocation.<Path>getArgument(1)));
            return null;
        }).when(storage).writeObject(eq(KEY), any(Path.class), eq("application/zip"));

        assertThat(service().archiveKey("localhost", "hashicorp", "random", "3.6.0", "linux_amd64")).contains(KEY);
        assertThat(written.get()).isEqualTo(ARCHIVE);
    }

    @Test
    void storedArchiveIsServedWithoutUpstream() {
        when(storage.objectExists(KEY)).thenReturn(true);

        assertThat(service().archiveKey("localhost", "hashicorp", "random", "3.6.0", "linux_amd64")).contains(KEY);
        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    @Test
    void shaMismatchStoresNothing() {
        stubDownloadDetails("c".repeat(64));

        assertThatThrownBy(() -> service().archiveKey("localhost", "hashicorp", "random", "3.6.0", "linux_amd64"))
                .isInstanceOf(MirrorUpstreamException.class).hasMessageContaining("Checksum mismatch");
        verify(storage, never()).writeObject(anyString(), any(), anyString());
    }

    @Test
    void archiveAboveSizeCapStoresNothing() {
        upstream.stubFor(get(ARCHIVE_PATH).willReturn(aResponse().withBody(new byte[1024 * 1024 + 1])));
        properties.setMaxArchiveSizeMb(1);

        assertThatThrownBy(() -> service().archiveKey("localhost", "hashicorp", "random", "3.6.0", "linux_amd64"))
                .isInstanceOf(MirrorUpstreamException.class).hasMessageContaining("larger than");
        verify(storage, never()).writeObject(anyString(), any(), anyString());
    }

    @Test
    void hostNotAllowlistedUsesStorageOnly() {
        when(storage.readObject("mirror/example.com/acme/thing/index.json"))
                .thenReturn(Optional.of(new ByteArrayInputStream("{\"versions\":{\"1.0.0\":{}}}".getBytes())));
        ProviderMirrorService service = service();

        assertThat(service.index("example.com", "acme", "thing")).contains("{\"versions\":{\"1.0.0\":{}}}");
        assertThat(service.archiveKey("example.com", "acme", "thing", "1.0.0", "linux_amd64")).isEmpty();
        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    @Test
    void pullThroughOffMakesNoOutboundCalls() {
        properties.setPullThroughEnabled(false);
        ProviderMirrorService service = service();

        assertThat(service.index("localhost", "hashicorp", "random")).isEmpty();
        assertThat(service.version("my-org", "localhost", "hashicorp", "random", "3.6.0")).isEmpty();
        assertThat(service.archiveKey("localhost", "hashicorp", "random", "3.6.0", "linux_amd64")).isEmpty();
        assertThat(upstream.getAllServeEvents()).isEmpty();
        verify(storage).readObject("mirror/localhost/hashicorp/random/index.json");
    }

    @Test
    void concurrentMissesFetchOnce() {
        upstream.stubFor(get(ARCHIVE_PATH).willReturn(aResponse().withBody(ARCHIVE).withFixedDelay(500)));
        AtomicBoolean stored = new AtomicBoolean();
        when(storage.objectExists(KEY)).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(true);
            return null;
        }).when(storage).writeObject(eq(KEY), any(Path.class), anyString());
        ProviderMirrorService service = service();

        CompletableFuture<Optional<String>> first = CompletableFuture.supplyAsync(
                () -> service.archiveKey("localhost", "hashicorp", "random", "3.6.0", "linux_amd64"));
        CompletableFuture<Optional<String>> second = CompletableFuture.supplyAsync(
                () -> service.archiveKey("localhost", "hashicorp", "random", "3.6.0", "linux_amd64"));

        assertThat(first.join()).contains(KEY);
        assertThat(second.join()).contains(KEY);
        upstream.verify(1, getRequestedFor(urlEqualTo(ARCHIVE_PATH)));
    }

    @Test
    void fetchesBeyondTheLimitAreRefusedAsBusy() throws Exception {
        properties.setMaxConcurrentFetches(1);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            release.await();
            return null;
        }).when(storage).writeObject(eq(KEY), any(Path.class), anyString());
        ProviderMirrorService service = service();
        service.fetchPermitWaitMillis = 0;

        CompletableFuture<Optional<String>> first = CompletableFuture.supplyAsync(
                () -> service.archiveKey("localhost", "hashicorp", "random", "3.6.0", "linux_amd64"));
        assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.archiveKey("localhost", "hashicorp", "random", "3.6.0", "darwin_arm64"))
                .isInstanceOf(MirrorBusyException.class);
        release.countDown();
        assertThat(first.join()).contains(KEY);
    }

    @Test
    void invalidStoredJsonIsAnInternalErrorNotAnUpstreamOne() {
        properties.setPullThroughEnabled(false);
        when(storage.readObject("mirror/registry.example.com/acme/thing/1.2.0.json"))
                .thenReturn(Optional.of(new ByteArrayInputStream("{not json".getBytes())));

        assertThatThrownBy(() -> service().version("my-org", "registry.example.com", "acme", "thing", "1.2.0"))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(MirrorUpstreamException.class)
                .hasMessageContaining("1.2.0.json");
    }

    @Test
    void storageOnlyRewritesArchiveUrlsAndKeepsHashes() throws Exception {
        properties.setPullThroughEnabled(false);
        when(storage.readObject("mirror/registry.example.com/acme/thing/1.2.0.json")).thenReturn(Optional.of(new ByteArrayInputStream("""
                {"archives":{
                  "linux_amd64":{"url":"https://elsewhere.example.com/x.zip","hashes":["h1:abc=","zh:%s"]},
                  "windows_386":{"url":"terraform-provider-thing_1.2.0_windows_386.zip"},
                  "../escape":{"url":"x.zip"}
                }}""".formatted("d".repeat(64)).getBytes())));

        JsonNode version = objectMapper.readTree(service().version("my-org", "registry.example.com", "acme", "thing", "1.2.0").orElseThrow());

        JsonNode archives = version.path("archives");
        assertThat(archives.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("linux_amd64", "windows_386");
        assertThat(archives.path("linux_amd64").path("url").asText()).startsWith("1.2.0/linux_amd64.zip?ticket=");
        assertThat(archives.path("linux_amd64").path("hashes")).extracting(JsonNode::asText)
                .containsExactly("h1:abc=", "zh:" + "d".repeat(64));
        assertThat(archives.path("windows_386").has("hashes")).isFalse();
        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    @Test
    void pathPartsAreValidated() {
        assertThat(ProviderMirrorService.isValidProvider("registry.terraform.io", "hashicorp", "aws")).isTrue();
        assertThat(ProviderMirrorService.isValidProvider("..", "hashicorp", "aws")).isFalse();
        assertThat(ProviderMirrorService.isValidProvider("a..b", "hashicorp", "aws")).isFalse();
        assertThat(ProviderMirrorService.isValidProvider("Registry.io", "hashicorp", "aws")).isFalse();
        assertThat(ProviderMirrorService.isValidProvider("registry.io", "hashi/corp", "aws")).isFalse();
        assertThat(ProviderMirrorService.isValidVersion("1.2.3-beta+meta")).isTrue();
        assertThat(ProviderMirrorService.isValidVersion("1..2")).isFalse();
        assertThat(ProviderMirrorService.isValidVersion(".1")).isFalse();
        assertThat(ProviderMirrorService.isValidPlatform("linux_amd64")).isTrue();
        assertThat(ProviderMirrorService.isValidPlatform("linux-amd64")).isFalse();
    }
}
