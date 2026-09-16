package io.terrakube.registry.service.inspect;

import io.terrakube.registry.controller.model.module.ModuleDetailsDTO;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.module.ModuleService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(details.readme()).isNull();
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
}
