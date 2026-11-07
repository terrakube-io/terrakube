package io.terrakube.registry;

import io.restassured.response.Response;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Operator-uploaded mirror files on local storage, served through the real HTTP stack (LOCAL auth). */
class ProviderMirrorTests extends OpenRegistryApplicationTests {

    private static final String HOST = "mirror-test.invalid";
    private final String type = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final byte[] archive = "provider-zip-bytes".getBytes();
    private Path providerDir;

    private String base() {
        return "/terraform/providers/mirror/v1/simple/" + HOST + "/acme/" + type;
    }

    @BeforeAll
    void writeMirrorFiles() throws IOException {
        Path hostDir = Path.of(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot", "local", "mirror", HOST);
        providerDir = hostDir.resolve("acme").resolve(type);
        Files.createDirectories(providerDir);
        Files.writeString(providerDir.resolve("index.json"), "{\"versions\":{\"1.0.0\":{}}}");
        Files.writeString(providerDir.resolve("1.0.0.json"), """
                {"archives":{"linux_amd64":{"url":"terraform-provider-%s_1.0.0_linux_amd64.zip","hashes":["h1:abc="]}}}"""
                .formatted(type));
        Files.write(providerDir.resolve("terraform-provider-" + type + "_1.0.0_linux_amd64.zip"), archive);
    }

    @AfterAll
    void deleteMirrorFiles() throws IOException {
        FileUtils.deleteDirectory(providerDir.toFile());
    }

    @Test
    void indexJsonIsServedFromStorage() {
        Response index = given().get(base() + "/index.json");
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(index.contentType()).startsWith("application/json");
        assertThat(index.asString()).isEqualTo("{\"versions\":{\"1.0.0\":{}}}");
    }

    @Test
    void versionJsonUrlsAreRewrittenWithTicketAndTheArchiveDownloads() {
        Response version = given().get(base() + "/1.0.0.json");
        assertThat(version.statusCode()).isEqualTo(200);
        String url = version.jsonPath().getString("archives.linux_amd64.url");
        assertThat(url).startsWith("1.0.0/linux_amd64.zip?ticket=");
        assertThat(version.jsonPath().getList("archives.linux_amd64.hashes")).containsExactly("h1:abc=");

        // Clients resolve the relative URL against the version document, as the protocol says.
        String archiveUrl = URI.create(base() + "/1.0.0.json").resolve(url).toString();
        Response download = given().urlEncodingEnabled(false).get(archiveUrl);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.contentType()).isEqualTo("application/zip");
        assertThat(download.asByteArray()).isEqualTo(archive);
    }

    @Test
    void dotDotVersionIsBadRequest() {
        assertThat(given().get(base() + "/1..0.json").statusCode()).isEqualTo(400);
    }

    @Test
    void unknownProviderIs404() {
        assertThat(given().get("/terraform/providers/mirror/v1/simple/" + HOST + "/acme/none/index.json").statusCode()).isEqualTo(404);
        assertThat(given().get(base() + "/9.9.9.json").statusCode()).isEqualTo(404);
        assertThat(given().get(base() + "/9.9.9/linux_amd64.zip").statusCode()).isEqualTo(404);
    }
}
