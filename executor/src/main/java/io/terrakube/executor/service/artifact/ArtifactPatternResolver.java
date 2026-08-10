package io.terrakube.executor.service.artifact;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the artifact globs a template or workspace declares into the globs to match: each entry is
 * split into patterns (new lines, or commas outside braces so {@code *.{zip,tar}} stays whole) and
 * its {@code ${VAR}} references are replaced from the job's environment variables. A pattern can be
 * prefixed with {@code optional:} when matching no file is fine; every other pattern must match.
 */
@Component
public class ArtifactPatternResolver {

    /** A pattern ending in this prefix may match nothing without failing the plan. */
    public static final String OPTIONAL_PREFIX = "optional:";

    /** A glob to match, and whether matching no file is acceptable. */
    public record ArtifactPattern(String glob, boolean optional) {
        @Override
        public String toString() {
            return optional ? OPTIONAL_PREFIX + glob : glob;
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

    public List<ArtifactPattern> resolve(List<String> patterns, Map<String, String> environmentVariables) {
        Map<String, String> env = environmentVariables == null ? Map.of() : environmentVariables;
        List<ArtifactPattern> resolved = new ArrayList<>();

        for (String entry : patterns) {
            for (String pattern : split(entry)) {
                String substituted = substitute(pattern, env);
                if (substituted == null) {
                    // A placeholder referenced a variable that isn't set - drop this whole pattern
                    // rather than substituting an empty string in place, which would otherwise
                    // turn e.g. "${ARTIFACT_PATH}/**" into the bogus, overly-broad pattern "/**".
                    continue;
                }
                // A variable's value can itself list several patterns.
                for (String piece : split(substituted)) {
                    boolean optional = piece.startsWith(OPTIONAL_PREFIX);
                    String glob = optional ? piece.substring(OPTIONAL_PREFIX.length()).trim() : piece;
                    if (!glob.isEmpty()) {
                        resolved.add(new ArtifactPattern(glob, optional));
                    }
                }
            }
        }

        return resolved;
    }

    /** Splits on new lines and on commas that are not inside {@code {...}}; drops blank pieces. */
    static List<String> split(String value) {
        List<String> pieces = new ArrayList<>();
        if (value == null) {
            return pieces;
        }
        StringBuilder current = new StringBuilder();
        int braceDepth = 0;
        for (char c : value.toCharArray()) {
            if (c == '{') {
                braceDepth++;
            } else if (c == '}' && braceDepth > 0) {
                braceDepth--;
            }
            if ((c == ',' && braceDepth == 0) || c == '\n' || c == '\r') {
                addPiece(pieces, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addPiece(pieces, current);
        return pieces;
    }

    private static void addPiece(List<String> pieces, StringBuilder current) {
        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) {
            pieces.add(trimmed);
        }
    }

    private String substitute(String pattern, Map<String, String> env) {
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = env.get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
