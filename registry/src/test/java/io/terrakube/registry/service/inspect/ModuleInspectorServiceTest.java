package io.terrakube.registry.service.inspect;

import io.terrakube.registry.controller.model.module.ModuleDetailsDTO;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.module.ModuleService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModuleInspectorServiceTest {

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));
                out.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static final Map<String, String> MODULE = Map.of(
            "main.tf", "resource \"aws_vpc\" \"main\" { cidr_block = var.cidr }\n",
            "variables.tf", "variable \"cidr\" {\n  type = string\n  description = \"VPC CIDR\"\n  default = \"10.0.0.0/16\"\n}\n",
            "outputs.tf", "output \"vpc_id\" {\n  description = \"The VPC id\"\n  value = aws_vpc.main.id\n}\n",
            "README.md", "# root",
            "terraform.tfvars", "cidr = \"1.2.3.4/32\"\n",
            "examples/basic/main.tf", "variable \"not_a_root_variable\" {}\n",
            "modules/subnet/main.tf", "variable \"az\" { type = string }\nresource \"aws_subnet\" \"this\" {}\n",
            "modules/subnet/README.md", "# subnet",
            "modules/subnet/nested/extra.tf", "output \"nested\" { value = 1 }\n",
            "modules/nat/main.tf", "variable \"count_nat\" { default = 1 }\n");

    private final ModuleInspectorService service = new ModuleInspectorService(mock(ModuleService.class), mock(StorageService.class));

    @Test
    void rootModuleListsOnlyItsOwnFilesAndDiscoversSubmodules() throws IOException {
        ModuleDetailsDTO details = service.inspect(zip(MODULE), "");

        assertThat(details.submodules()).containsExactly("nat", "subnet");
        assertThat(details.variables()).containsExactly(new ModuleDetailsDTO.Variable("cidr", "string", "VPC CIDR", "\"10.0.0.0/16\""));
        assertThat(details.outputs()).containsExactly(new ModuleDetailsDTO.Output("vpc_id", "The VPC id"));
        assertThat(details.resources()).containsExactly(new ModuleDetailsDTO.Resource("aws_vpc", "main"));
        assertThat(details.readme()).isEqualTo("# root");
    }

    @Test
    void submoduleIncludesItsNestedFilesAndReadme() throws IOException {
        ModuleDetailsDTO details = service.inspect(zip(MODULE), "subnet");

        assertThat(details.variables()).extracting(ModuleDetailsDTO.Variable::name).containsExactly("az");
        assertThat(details.outputs()).extracting(ModuleDetailsDTO.Output::name).containsExactly("nested");
        assertThat(details.resources()).containsExactly(new ModuleDetailsDTO.Resource("aws_subnet", "this"));
        assertThat(details.readme()).isEqualTo("# subnet");
    }

    @Test
    void detailsResolvesTheVersionBeforeDownloadingSoTheArchiveExists() throws IOException {
        ModuleService moduleService = mock(ModuleService.class);
        StorageService storageService = mock(StorageService.class);
        when(storageService.downloadModule("org", "vpc", "aws", "1.0.0")).thenReturn(zip(MODULE));

        ModuleDetailsDTO details = new ModuleInspectorService(moduleService, storageService).details("org", "vpc", "aws", "1.0.0", "");

        verify(moduleService).getModuleVersionPath("org", "vpc", "aws", "1.0.0");
        assertThat(details.variables()).hasSize(1);
    }

    @Test
    void readmeMatchesAnyCaseAndIsNullWhenMissing() throws IOException {
        byte[] archive = zip(Map.of(
                "Readme.md", "# root",
                "modules/a/readme.md", "# a",
                "modules/a/docs/README.md", "# not the submodule readme",
                "modules/b/main.tf", ""));

        assertThat(service.inspect(archive, "").readme()).isEqualTo("# root");
        assertThat(service.inspect(archive, "a").readme()).isEqualTo("# a");
        assertThat(service.inspect(archive, "b").readme()).isNull();
    }

    @Test
    void rejectsArchivesWhoseExtractedTextIsTooLarge() throws IOException {
        byte[] oversizedFile = zip(Map.of("main.tf", " ".repeat(4 * 1024 * 1024 + 1), "modules/other/main.tf", ""));
        Map<String, String> manyFiles = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            manyFiles.put("f" + i + ".tf", " ".repeat(4 * 1024 * 1024));
        }

        assertThatThrownBy(() -> service.inspect(oversizedFile, ""))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
        assertThatThrownBy(() -> service.inspect(zip(manyFiles), "")).isInstanceOf(ResponseStatusException.class);
        // Files outside the inspected module are never read, whatever their size.
        assertThat(service.inspect(oversizedFile, "other").variables()).isEmpty();
    }

    @Test
    void unknownSubmoduleIsNotFoundAndDotSegmentsAreNotSubmodules() throws IOException {
        byte[] archive = zip(Map.of("modules/./main.tf", "", "modules/../main.tf", "", "modules/ok/main.tf", ""));

        assertThat(service.inspect(archive, "").submodules()).containsExactly("ok");
        for (String submodule : new String[]{"missing", "..", "ok/../ok", "ok/"}) {
            assertThatThrownBy(() -> service.inspect(archive, submodule))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }
}
