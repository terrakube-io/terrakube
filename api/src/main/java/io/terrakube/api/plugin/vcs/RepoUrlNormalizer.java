package io.terrakube.api.plugin.vcs;

import java.net.URI;

public final class RepoUrlNormalizer {

    private RepoUrlNormalizer() {
    }

    public static String normalize(String url) {
        if (url == null) {
            return null;
        }
        String normalized = url.trim();

        // Strip userinfo component (e.g. "org@" in https://org@dev.azure.com/...)
        // and remove the Azure DevOps "_git" path segment so that
        // "https://org@dev.azure.com/org/proj/_git/repo" and
        // "https://dev.azure.com/org/proj/repo" coalesce to the same key.
        try {
            URI uri = new URI(normalized);
            if (uri.getUserInfo() != null) {
                // Rebuild without userinfo
                normalized = new URI(uri.getScheme(), null, uri.getHost(),
                        uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
            }
        } catch (Exception e) {
            // Not a valid URI — continue with the raw string
        }

        // Remove /_git/ path segment (Azure DevOps specific)
        normalized = normalized.replaceFirst("/_git/", "/");

        normalized = normalized.toLowerCase();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }
}

