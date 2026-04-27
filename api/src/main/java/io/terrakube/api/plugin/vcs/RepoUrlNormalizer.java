package io.terrakube.api.plugin.vcs;

public final class RepoUrlNormalizer {

    private RepoUrlNormalizer() {
    }

    public static String normalize(String url) {
        if (url == null) {
            return null;
        }
        String normalized = url.trim().toLowerCase();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }
}
