package io.terrakube.api.plugin.scheduler.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure parsing/URL helpers used when importing providers from a private
 * Terraform registry or a repository release page. No Spring context or network is required.
 */
class ProviderRefreshJobTest {

    private final ProviderRefreshJob job = new ProviderRefreshJob();

    // ---- SHA256SUMS parsing -------------------------------------------------

    @Test
    void parseShaSumsReadsEveryEntry() {
        String content = """
                abc123  terraform-provider-foo_1.2.3_linux_amd64.zip
                def456  terraform-provider-foo_1.2.3_darwin_arm64.zip
                """;

        Map<String, String> sums = ProviderRefreshJob.parseShaSums(content);

        assertEquals(2, sums.size());
        assertEquals("abc123", sums.get("terraform-provider-foo_1.2.3_linux_amd64.zip"));
        assertEquals("def456", sums.get("terraform-provider-foo_1.2.3_darwin_arm64.zip"));
    }

    @Test
    void parseShaSumsHandlesBinaryMarkerAndBlankLines() {
        String content = "abc123 *terraform-provider-foo_1.2.3_linux_amd64.zip\n\n   \n";

        Map<String, String> sums = ProviderRefreshJob.parseShaSums(content);

        assertEquals(1, sums.size());
        assertEquals("abc123", sums.get("terraform-provider-foo_1.2.3_linux_amd64.zip"));
    }

    @Test
    void parseShaSumsHandlesNull() {
        assertTrue(ProviderRefreshJob.parseShaSums(null).isEmpty());
    }

    // ---- filename -> platform ----------------------------------------------

    @Test
    void platformFromFilenameExtractsOsAndArch() {
        String[] platform = ProviderRefreshJob.platformFromFilename("terraform-provider-foo_1.2.3_linux_amd64.zip");
        assertNotNull(platform);
        assertEquals("linux", platform[0]);
        assertEquals("amd64", platform[1]);
    }

    @Test
    void platformFromFilenameIgnoresNonZipAssets() {
        assertNull(ProviderRefreshJob.platformFromFilename("terraform-provider-foo_1.2.3_SHA256SUMS"));
        assertNull(ProviderRefreshJob.platformFromFilename("terraform-provider-foo_1.2.3_SHA256SUMS.sig"));
        assertNull(ProviderRefreshJob.platformFromFilename("terraform-provider-foo_1.2.3_manifest.json"));
        assertNull(ProviderRefreshJob.platformFromFilename(null));
    }

    // ---- asset URL building -------------------------------------------------

    @Test
    void buildAssetUrlAppendsFilenameToFlatBase() {
        String url = ProviderRefreshJob.buildAssetUrl(
                "https://files.example.com/providers/", "1.2.3",
                "terraform-provider-foo_1.2.3_linux_amd64.zip");
        assertEquals("https://files.example.com/providers/terraform-provider-foo_1.2.3_linux_amd64.zip", url);
    }

    @Test
    void buildAssetUrlSubstitutesVersionPlaceholder() {
        String url = ProviderRefreshJob.buildAssetUrl(
                "https://github.com/org/terraform-provider-foo/releases/download/v{version}", "1.2.3",
                "terraform-provider-foo_1.2.3_linux_amd64.zip");
        assertEquals(
                "https://github.com/org/terraform-provider-foo/releases/download/v1.2.3/terraform-provider-foo_1.2.3_linux_amd64.zip",
                url);
    }

    // ---- repository version list -------------------------------------------

    @Test
    void parseVersionListTrimsDedupesAndSkipsBlanks() {
        List<String> versions = ProviderRefreshJob.parseVersionList(" 1.0.0, 1.1.0 ,1.0.0,, 2.0.0 ");
        assertEquals(List.of("1.0.0", "1.1.0", "2.0.0"), versions);
        assertTrue(ProviderRefreshJob.parseVersionList(null).isEmpty());
        assertTrue(ProviderRefreshJob.parseVersionList("   ").isEmpty());
    }

    // ---- service discovery --------------------------------------------------

    @Test
    void providersBaseFromDiscoveryResolvesRelativePath() {
        String body = "{\"modules.v1\":\"/terraform/modules/v1/\",\"providers.v1\":\"/terraform/providers/v1/\"}";
        String base = job.providersBaseFromDiscovery(body, "https://registry.example.com");
        assertEquals("https://registry.example.com/terraform/providers/v1/", base);
    }

    @Test
    void providersBaseFromDiscoveryKeepsAbsoluteUrlAndEnsuresTrailingSlash() {
        String body = "{\"providers.v1\":\"https://cdn.example.com/v1/providers\"}";
        String base = job.providersBaseFromDiscovery(body, "https://registry.example.com");
        assertEquals("https://cdn.example.com/v1/providers/", base);
    }

    @Test
    void providersBaseFromDiscoveryReturnsNullWhenMissing() {
        assertNull(job.providersBaseFromDiscovery("{\"modules.v1\":\"/m/\"}", "https://registry.example.com"));
        assertNull(job.providersBaseFromDiscovery("", "https://registry.example.com"));
        assertNull(job.providersBaseFromDiscovery(null, "https://registry.example.com"));
    }

    // ---- registry version listing ------------------------------------------

    @Test
    void parseRegistryVersionsReadsVersionsProtocolsAndPlatforms() {
        String body = """
                {"versions":[
                  {"version":"1.0.0","protocols":["5.0","6.0"],
                   "platforms":[{"os":"linux","arch":"amd64"},{"os":"darwin","arch":"arm64"}]},
                  {"version":"1.1.0","protocols":["6.0"],"platforms":[{"os":"linux","arch":"arm64"}]}
                ]}
                """;

        List<ProviderRefreshJob.RegistryVersion> versions = job.parseRegistryVersions(body);

        assertEquals(2, versions.size());
        assertEquals("1.0.0", versions.get(0).version);
        assertEquals("5.0,6.0", versions.get(0).protocols);
        assertEquals(2, versions.get(0).platforms.size());
        assertEquals("linux", versions.get(0).platforms.get(0)[0]);
        assertEquals("amd64", versions.get(0).platforms.get(0)[1]);
    }

    @Test
    void parseRegistryVersionsHandlesEmptyAndNull() {
        assertTrue(job.parseRegistryVersions(null).isEmpty());
        assertTrue(job.parseRegistryVersions("{}").isEmpty());
    }
}
