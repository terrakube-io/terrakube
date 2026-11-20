package io.terrakube.api.plugin.vcs;

import io.terrakube.api.rs.vcs.Vcs;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Validates that outbound HTTP URLs extracted from VCS webhook payloads or headers
 * target the same origin and base path as the configured {@link Vcs#getApiUrl()}.
 * Fails closed on any missing, malformed, or cross-origin URL.
 */
@Slf4j
public final class VcsApiUrlValidator {

    private VcsApiUrlValidator() {
    }

    public static boolean isAllowedForVcs(Vcs vcs, String candidateUrl) {
        if (vcs == null || vcs.getApiUrl() == null || vcs.getApiUrl().isBlank()
                || candidateUrl == null || candidateUrl.isBlank()) {
            return false;
        }
        String trimmedCandidate = candidateUrl.trim();
        if (containsUnsafeCharactersOrTraversal(trimmedCandidate)) {
            return false;
        }

        URI baseUri;
        URI candidateUri;
        try {
            baseUri = new URI(vcs.getApiUrl().trim());
            candidateUri = new URI(trimmedCandidate);
        } catch (URISyntaxException _) {
            return false;
        }

        if (candidateUri.getRawFragment() != null) {
            return false;
        }
        if (!isSameOrigin(baseUri, candidateUri)) {
            log.warn("Blocked VCS API request: candidate host '{}' (port {}) does not match configured VCS apiUrl host '{}' (port {}). "
                    + "Ensure Vcs.apiUrl uses the canonical hostname emitted in webhook payloads.",
                    candidateUri.getHost(), effectivePort(candidateUri),
                    baseUri.getHost(), effectivePort(baseUri));
            return false;
        }
        if (hasDotTraversalSegment(candidateUri.getRawPath()) || hasDotTraversalSegment(candidateUri.getPath())) {
            return false;
        }

        String basePath = normalizeBasePath(baseUri.normalize().getRawPath());
        String candidatePath = candidateUri.normalize().getRawPath();
        if (candidatePath == null) {
            candidatePath = "";
        }

        if (!basePath.isEmpty()) {
            return candidatePath.equals(basePath) || candidatePath.startsWith(basePath + "/");
        }
        return candidatePath.isEmpty() || candidatePath.startsWith("/");
    }

    public static boolean isSameOrigin(URI expected, URI candidate) {
        if (expected == null || candidate == null) {
            return false;
        }
        if (expected.getRawUserInfo() != null || candidate.getRawUserInfo() != null) {
            return false;
        }
        String expectedScheme = expected.getScheme();
        String candidateScheme = candidate.getScheme();
        if (expectedScheme == null || candidateScheme == null || !expectedScheme.equalsIgnoreCase(candidateScheme)) {
            return false;
        }
        if (!"http".equalsIgnoreCase(expectedScheme) && !"https".equalsIgnoreCase(expectedScheme)) {
            return false;
        }
        String expectedHost = expected.getHost();
        String candidateHost = candidate.getHost();
        if (expectedHost == null || expectedHost.isBlank() || candidateHost == null || candidateHost.isBlank()) {
            return false;
        }
        if (!expectedHost.equalsIgnoreCase(candidateHost)) {
            return false;
        }
        int expectedPort = effectivePort(expected);
        int candidatePort = effectivePort(candidate);
        return expectedPort != -1 && expectedPort == candidatePort;
    }

    static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
    }

    private static boolean containsUnsafeCharactersOrTraversal(String url) {
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0' || c == '\\') {
                return true;
            }
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("%2e%2e") || lower.contains("%5c") || lower.contains("%2f");
    }

    private static boolean hasDotTraversalSegment(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if ("..".equals(segment) || ".".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeBasePath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "";
        }
        String normalized = path;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return "/".equals(normalized) ? "" : normalized;
    }
}
