package io.terrakube.executor.service.artifact;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ArtifactPatternResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

    public List<String> resolve(List<String> patterns, Map<String, String> environmentVariables) {
        Map<String, String> env = environmentVariables == null ? Map.of() : environmentVariables;
        List<String> resolved = new ArrayList<>();

        for (String pattern : patterns) {
            String substituted = substitute(pattern, env);
            if (substituted == null) {
                // A placeholder referenced a variable that isn't set - drop this whole pattern
                // entry rather than substituting an empty string in place, which would otherwise
                // turn e.g. "${ARTIFACT_PATH}/**" into the bogus, overly-broad pattern "/**".
                continue;
            }
            for (String piece : substituted.split(",")) {
                String trimmed = piece.trim();
                if (!trimmed.isEmpty()) {
                    resolved.add(trimmed);
                }
            }
        }

        return resolved;
    }

    private String substitute(String pattern, Map<String, String> env) {
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!env.containsKey(key)) {
                return null;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(env.get(key)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
