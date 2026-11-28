package io.terrakube.executor.service.infracost;

import io.terrakube.executor.service.scripts.bash.ProcessLauncher;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strips operator/Infracost secrets and control settings before launching Terraform, Bash,
 * Groovy, or OPA subprocesses so user-supplied code or workspace templates cannot read them.
 */
public final class InfracostEnvSanitizer {

    public static final String INFRACOST_API_KEY = "INFRACOST_API_KEY";
    public static final String INFRACOST_PRICING_API_ENDPOINT = "INFRACOST_PRICING_API_ENDPOINT";
    public static final String INFRACOST_ENABLE_DASHBOARD = "INFRACOST_ENABLE_DASHBOARD";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            INFRACOST_API_KEY,
            INFRACOST_PRICING_API_ENDPOINT,
            INFRACOST_ENABLE_DASHBOARD
    );

    private InfracostEnvSanitizer() {
    }

    public static boolean isSensitiveInfracostKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return SENSITIVE_KEYS.contains(key.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Returns a shallow copy of {@code source} with any sensitive Infracost keys present in
     * {@code source} or {@code System.getenv()} mapped to {@code null} (without mutating {@code source}).
     * Passing {@code null} values causes {@code terraform-client}'s {@code ProcessLauncher} to invoke
     * {@code ProcessBuilder.environment().remove(key)}, removing both job-level and ambient
     * container environment values while leaving maps untouched when none of those keys are set.
     */
    public static HashMap<String, String> sanitizeForTerraform(Map<String, String> source) {
        return sanitizeForTerraform(source, System.getenv().keySet());
    }

    static HashMap<String, String> sanitizeForTerraform(Map<String, String> source, Set<String> systemEnvKeys) {
        HashMap<String, String> sanitized = source != null ? new HashMap<>(source) : new HashMap<>();
        Set<String> keysToUnset = new LinkedHashSet<>();
        if (source != null) {
            for (String key : source.keySet()) {
                if (isSensitiveInfracostKey(key)) {
                    keysToUnset.add(key);
                }
            }
        }
        if (systemEnvKeys != null) {
            for (String envKey : systemEnvKeys) {
                if (isSensitiveInfracostKey(envKey)) {
                    keysToUnset.add(envKey);
                }
            }
        }
        for (String key : keysToUnset) {
            sanitized.put(key, null);
        }
        return sanitized;
    }

    @SafeVarargs
    public static void stripFromProcessLauncher(ProcessLauncher launcher, Collection<String>... candidateKeySets) {
        if (launcher == null) {
            return;
        }
        Set<String> keysToUnset = new LinkedHashSet<>(SENSITIVE_KEYS);
        if (candidateKeySets != null) {
            for (Collection<String> keySet : candidateKeySets) {
                if (keySet == null) {
                    continue;
                }
                for (String key : keySet) {
                    if (isSensitiveInfracostKey(key)) {
                        keysToUnset.add(key);
                    }
                }
            }
        }
        for (String envKey : System.getenv().keySet()) {
            if (isSensitiveInfracostKey(envKey)) {
                keysToUnset.add(envKey);
            }
        }
        for (String key : keysToUnset) {
            launcher.setEnvironmentVariable(key, null);
        }
    }

    public static void stripFromEnvironmentMap(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return;
        }
        env.keySet().removeIf(InfracostEnvSanitizer::isSensitiveInfracostKey);
    }
}
