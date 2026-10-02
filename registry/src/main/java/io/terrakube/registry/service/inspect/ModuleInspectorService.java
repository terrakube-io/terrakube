package io.terrakube.registry.service.inspect;

import io.terrakube.registry.configuration.CacheConfig;
import io.terrakube.registry.controller.model.module.ModuleDetailsDTO;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.module.ModuleService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
    private static final String README = "README.md";
    // Only the extracted text is held in memory, so these bound a request's heap on a huge or crafted archive.
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

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
        long totalBytes = 0;

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith(SUBMODULES_DIR)) {
                    String[] parts = name.split("/");
                    if (parts.length > 2 && !parts[1].isEmpty() && !parts[1].equals(".") && !parts[1].equals("..")) {
                        submodules.add(parts[1]);
                    }
                }
                // The root module is only its top-level .tf files, as Terraform reads it. A submodule takes
                // everything under modules/<name>/, nested directories included, to match the old UI.
                if (!name.startsWith(prefix) || (prefix.isEmpty() && name.contains("/"))) {
                    continue;
                }
                String file = name.substring(prefix.length());
                boolean isTerraform = file.endsWith(".tf");
                boolean isReadme = file.equalsIgnoreCase(README);
                if (!isTerraform && !isReadme) {
                    continue;
                }
                byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
                totalBytes += bytes.length;
                if (bytes.length > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Module archive is too large to inspect: " + name);
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (isTerraform) {
                    hcl.append('\n').append(text);
                } else {
                    readme = text;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Module archive could not be read", e);
        }

        // A 404 here also keeps arbitrary ?submodule= values out of the details cache: exceptions are not cached.
        if (!prefix.isEmpty() && !submodules.contains(submodule)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submodule not found: " + submodule);
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
