package io.terrakube.registry.service.inspect;

import io.terrakube.registry.configuration.CacheConfig;
import io.terrakube.registry.controller.model.module.ModuleDetailsDTO;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.module.ModuleService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a module version's zip straight from storage and extracts what the detail page shows.
 * Replaces the browser downloading the whole archive and parsing HCL client-side.
 */
@Service
@AllArgsConstructor
@Slf4j
public class ModuleInspectorService {

    private static final String SUBMODULES_DIR = "modules/";

    private final ModuleService moduleService;
    private final StorageService storageService;

    @Cacheable(value = CacheConfig.MODULE_DETAILS_CACHE, sync = true)
    public ModuleDetailsDTO details(String organization, String module, String provider, String version, String submodule) {
        // Resolving the path is what clones and packs the version on first request; cached in ModuleService.
        moduleService.getModuleVersionPath(organization, module, provider, version);
        return inspect(storageService.downloadModule(organization, module, provider, version), submodule);
    }

    ModuleDetailsDTO inspect(byte[] zip, String submodule) {
        String prefix = submodule == null || submodule.isEmpty() ? "" : SUBMODULES_DIR + submodule + "/";
        TreeSet<String> submodules = new TreeSet<>();
        StringBuilder hcl = new StringBuilder();
        String readme = null;

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith(SUBMODULES_DIR)) {
                    String[] parts = name.split("/");
                    if (parts.length > 2 && !parts[1].isEmpty()) {
                        submodules.add(parts[1]);
                    }
                }
                boolean isTerraform = name.endsWith(".tf");
                if (prefix.isEmpty()) {
                    if (isTerraform && !name.contains("/")) {
                        hcl.append('\n').append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                } else if (name.startsWith(prefix)) {
                    if (isTerraform) {
                        hcl.append('\n').append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    } else if (name.equals(prefix + "README.md")) {
                        readme = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Module archive could not be read", e);
        }

        Map<String, ModuleDetailsDTO.Variable> variables = new LinkedHashMap<>();
        Map<String, ModuleDetailsDTO.Output> outputs = new LinkedHashMap<>();
        List<ModuleDetailsDTO.Resource> resources = new ArrayList<>();
        for (HclBlockScanner.Block block : HclBlockScanner.topLevelBlocks(hcl.toString())) {
            switch (block.type()) {
                case "variable" -> {
                    Map<String, String> attributes = HclBlockScanner.attributes(block.body());
                    variables.put(block.label(0), new ModuleDetailsDTO.Variable(
                            block.label(0),
                            attributes.get("type"),
                            HclBlockScanner.unquote(attributes.get("description")),
                            attributes.get("default")));
                }
                case "output" -> {
                    Map<String, String> attributes = HclBlockScanner.attributes(block.body());
                    outputs.put(block.label(0), new ModuleDetailsDTO.Output(
                            block.label(0),
                            HclBlockScanner.unquote(attributes.get("description"))));
                }
                case "resource" -> resources.add(new ModuleDetailsDTO.Resource(block.label(0), block.label(1)));
                default -> {
                }
            }
        }
        return new ModuleDetailsDTO(
                new ArrayList<>(submodules),
                new ArrayList<>(variables.values()),
                new ArrayList<>(outputs.values()),
                resources,
                readme);
    }
}
