package io.terrakube.executor.service.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared OS, CPU architecture, and semantic version normalization helpers for
 * external binary managers (OPA, Infracost).
 */
public final class BinaryPlatformUtils {

    public static final Pattern SEMVER_PATTERN = Pattern.compile("^[0-9]++(?:\\.[0-9]++)*+(?:-[a-zA-Z0-9.]++)?+$");

    private BinaryPlatformUtils() {
    }

    public static String normalizeAndValidateVersion(
            String version,
            String configuredDefault,
            String fallbackDefault,
            String toolName) {
        String effective = (version != null && !version.isBlank()) ? version.trim() : configuredDefault;
        if (effective == null || effective.isBlank()) {
            effective = fallbackDefault;
        }
        if (effective.startsWith("v") || effective.startsWith("V")) {
            effective = effective.substring(1).trim();
        }
        if (!SEMVER_PATTERN.matcher(effective).matches()) {
            throw new IllegalArgumentException(String.format(
                    "Invalid %s version format: '%s'. Expected semantic version.", toolName, version));
        }
        return effective;
    }

    public static String resolveArchitecture() {
        String arch = System.getProperty("os.arch", "amd64").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64") || arch.contains("armv8")) {
            return "arm64";
        }
        return "amd64";
    }

    public static String resolveOs() {
        String os = System.getProperty("os.name", "linux").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        } else if (os.contains("win")) {
            return "windows";
        }
        return "linux";
    }
}
