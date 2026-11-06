package io.terrakube.api;

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
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Keeps docs/single-domain/route-table.yaml aligned with the endpoints the API serves: every served
 * path must sit under one of the api entry's served_roots, and every listed root must be served.
 */
class RouteTableDriftTests extends ServerApplicationTests {

    private static final Path TABLE = Path.of("..", "docs", "single-domain", "route-table.yaml");
    private static final Set<String> IGNORED = Set.of("/error");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    Environment environment;

    @Test
    void routeTableMatchesServedEndpoints() throws IOException {
        List<String> roots = servedRoots(Files.readAllLines(TABLE), "api");
        Set<String> served = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> served.addAll(info.getPatternValues()));
        for (String property : List.of("elide.json-api.path", "elide.graphql.path", "elide.api-docs.path",
                "spring.graphql.websocket.path")) {
            String value = environment.getProperty(property);
            if (value != null && !value.isBlank()) {
                served.add(value);
            }
        }
        served.removeIf(path -> IGNORED.contains(path) || path.startsWith("/actuator"));

        List<String> uncovered = served.stream().filter(path -> roots.stream().noneMatch(root -> covers(root, path))).toList();
        List<String> stale = roots.stream().filter(root -> served.stream().noneMatch(path -> covers(root, path))).toList();
        assertTrue(uncovered.isEmpty(), "served but missing from route-table.yaml served_roots: " + uncovered);
        assertTrue(stale.isEmpty(), "listed in route-table.yaml served_roots but not served: " + stale);
    }

    static boolean covers(String root, String path) {
        return path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/");
    }

    /** served_roots of the first route whose component matches (small YAML subset, see the file). */
    static List<String> servedRoots(List<String> lines, String component) {
        List<String> roots = new ArrayList<>();
        boolean inRoute = false;
        boolean inRoots = false;
        for (String raw : lines) {
            String line = raw.replaceAll("\\s+#.*$", "");
            if (line.startsWith("  - prefix:")) {
                if (inRoute && !roots.isEmpty()) {
                    break;
                }
                inRoute = false;
                inRoots = false;
            } else if (line.trim().equals("component: " + component)) {
                inRoute = true;
            } else if (inRoute && line.trim().startsWith("served_roots:")) {
                inRoots = true;
                String inline = line.substring(line.indexOf(':') + 1).trim();
                if (inline.startsWith("[")) {
                    for (String item : inline.replaceAll("[\\[\\]]", "").split(",")) {
                        roots.add(item.trim());
                    }
                    inRoots = false;
                }
            } else if (inRoots && line.trim().startsWith("- ")) {
                roots.add(line.trim().substring(2).trim());
            } else if (inRoots && !line.isBlank()) {
                inRoots = false;
            }
        }
        return roots;
    }
}
