package io.terrakube.executor.service.workspace;

import com.sun.net.httpserver.HttpServer;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.terraform.TerraformExecutor;
import io.terrakube.executor.service.workspace.security.RunnerTokenHolder;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class HardenedSetupWorkspaceTest {

    @TempDir
    Path home;

    HttpServer server;
    String previousHome;
    List<String> authorizations = new CopyOnWriteArrayList<>();
    TerrakubeClient terrakubeClient = mock(TerrakubeClient.class);

    @BeforeEach
    void setup() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        byte[] archive = archive("main.tf", "resource \"terraform_data\" \"a\" {}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tfstate/v1/runner/configuration", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, archive.length);
            exchange.getResponseBody().write(archive);
            exchange.close();
        });
        server.start();
        RunnerTokenHolder.set("runner.jwt.token");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        RunnerTokenHolder.clear();
        System.setProperty("user.home", previousHome);
    }

    @Test
    void hardenedRunnersUseTheApiPreparedSourceInsteadOfCloning() throws Exception {
        String apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        SetupWorkspaceImpl setup = new SetupWorkspaceImpl(mock(WorkspaceSecurity.class), true, mock(TerraformExecutor.class), apiUrl, terrakubeClient);
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("o");
        job.setWorkspaceId("w");
        job.setJobId("1");
        job.setStepId("s");
        job.setType("terraformPlan");
        job.setBranch("main");
        job.setSource("git://unreachable.invalid/repo.git");
        job.setVcsType("PUBLIC");
        job.setEnvironmentVariables(new HashMap<>());

        File folder = setup.prepareWorkspace(job);

        assertThat(Files.readString(folder.toPath().resolve("main.tf"))).contains("terraform_data");
        assertThat(authorizations).containsExactly("Bearer runner.jwt.token");
        verifyNoInteractions(terrakubeClient);
    }

    private static byte[] archive(String name, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(data.length);
            tar.putArchiveEntry(entry);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
}
