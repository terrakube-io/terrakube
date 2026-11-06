package io.terrakube.registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Keeps the registry entry of docs/single-domain/route-table.yaml aligned with served endpoints. */
class RouteTableDriftTests extends OpenRegistryApplicationTests {

    private static final Path TABLE = Path.of("..", "docs", "single-domain", "route-table.yaml");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void routeTableMatchesServedEndpoints() throws IOException {
        List<String> roots = servedRoots(Files.readAllLines(TABLE));
        assertFalse(roots.isEmpty(), "registry entry has no served_roots");
        Set<String> served = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> served.addAll(info.getPatternValues()));
        served.removeIf(path -> path.equals("/error") || path.startsWith("/actuator"));

        List<String> uncovered = served.stream().filter(path -> roots.stream().noneMatch(root -> covers(root, path))).toList();
        List<String> stale = roots.stream().filter(root -> served.stream().noneMatch(path -> covers(root, path))).toList();
        assertTrue(uncovered.isEmpty(), "served but missing from route-table.yaml served_roots: " + uncovered);
        assertTrue(stale.isEmpty(), "listed in route-table.yaml served_roots but not served: " + stale);
    }

    private static boolean covers(String root, String path) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static List<String> servedRoots(List<String> lines) {
        boolean inRoute = false;
        for (String raw : lines) {
            String line = raw.replaceAll("\\s+#.*$", "").trim();
            if (line.startsWith("- prefix:")) {
                inRoute = false;
            } else if (line.equals("component: registry")) {
                inRoute = true;
            } else if (inRoute && line.startsWith("served_roots:")) {
                List<String> roots = new ArrayList<>();
                for (String item : line.substring(line.indexOf('[') + 1, line.lastIndexOf(']')).split(",")) {
                    roots.add(item.trim());
                }
                return roots;
            }
        }
        return List.of();
    }
}
