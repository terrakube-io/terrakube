package io.terrakube.api.plugin.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the {@code variables} a Terraform or OpenTofu CLI sends when it creates a run through the Terraform
 * Cloud compatible Runs API.
 *
 * <p>The CLI collects the values of {@code -var}, {@code -var-file} and {@code TF_VAR_} inputs and sends each as
 * {@code {"key": "name", "value": "<HCL literal>"}}. The value is always an HCL expression, so a string arrives
 * quoted ({@code "\"from-file\""}), while numbers, booleans, lists and objects arrive as written in HCL
 * ({@code 5}, {@code true}, {@code ["a", "b"]}).
 *
 * <p>The executor passes variables to Terraform as {@code --var name=value}, where Terraform parses the value by
 * the declared type of the variable: a string variable takes the text as it is, every other type takes HCL. So a
 * quoted string literal is unquoted here and everything else is passed through unchanged. A variable without a
 * type constraint always takes a string through {@code --var}, which is also what {@code -var} gives on a local
 * run, but a list or map given through {@code -var-file} for such a variable arrives as its HCL text.
 */
public final class CliRunVariables {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private CliRunVariables() {
    }

    /**
     * @param attribute the {@code data.attributes.variables} value of the create run request, or null
     * @return the value to pass for each variable name, in request order. A variable whose value is the HCL
     *         literal {@code null} is left out, so the workspace value (or the default) applies.
     * @throws IllegalArgumentException when an entry is malformed, naming the variable but never its value
     */
    public static Map<String, String> parse(Object attribute) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (attribute == null) {
            return variables;
        }
        if (!(attribute instanceof List<?> entries)) {
            throw new IllegalArgumentException("Run variables must be a list of key and value pairs");
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> pair) || !(pair.get("key") instanceof String key)
                    || !(pair.get("value") instanceof String value)) {
                throw new IllegalArgumentException("Each run variable needs a string key and a string value");
            }
            if (!NAME.matcher(key).matches()) {
                throw new IllegalArgumentException("Run variable names must be valid Terraform variable names");
            }
            if (variables.containsKey(key)) {
                throw new IllegalArgumentException("Run variable '" + key + "' is given more than once");
            }
            String decoded = fromHcl(key, value);
            if (decoded != null) {
                variables.put(key, decoded);
            }
        }
        return variables;
    }

    /** Returns the value for {@code --var}, or null for the literal {@code null}. */
    static String fromHcl(String key, String hcl) {
        String literal = hcl.strip();
        if (literal.isEmpty()) {
            throw new IllegalArgumentException("Run variable '" + key + "' has an empty value");
        }
        if ("null".equals(literal)) {
            return null;
        }
        if (literal.charAt(0) == '"') {
            return unquote(key, literal);
        }
        return literal;
    }

    /**
     * Decodes a complete quoted HCL string literal, the way hclwrite writes one: {@code \"}, {@code \\},
     * {@code \n}, {@code \r}, {@code \t}, {@code \}{@code uXXXX} and {@code \}{@code UXXXXXXXX} escapes, and
     * {@code $${} and {@code %%{} for a literal {@code ${} and {@code %{}.
     */
    private static String unquote(String key, String literal) {
        int last = literal.length() - 1;
        if (last < 1 || literal.charAt(last) != '"') {
            throw invalid(key);
        }
        StringBuilder out = new StringBuilder(last);
        int i = 1;
        while (i < last) {
            char c = literal.charAt(i);
            if (c == '"') {
                // An unescaped quote before the end: this is not a single string literal.
                throw invalid(key);
            }
            if (c == '\\') {
                i = escape(key, literal, i, last, out);
            } else if ((c == '$' || c == '%') && i + 1 < last && literal.charAt(i + 1) == c
                    && i + 2 < last && literal.charAt(i + 2) == '{') {
                // $${ and %%{ are the escapes for ${ and %{ in a template.
                out.append(c).append('{');
                i += 3;
            } else if ((c == '$' || c == '%') && i + 1 < last && literal.charAt(i + 1) == '{') {
                // A real interpolation or directive: a value written by the CLI never contains one.
                throw invalid(key);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int escape(String key, String literal, int at, int last, StringBuilder out) {
        if (at + 1 >= last) {
            throw invalid(key);
        }
        char next = literal.charAt(at + 1);
        switch (next) {
            case 'n' -> out.append('\n');
            case 'r' -> out.append('\r');
            case 't' -> out.append('\t');
            case '"' -> out.append('"');
            case '\\' -> out.append('\\');
            case 'u', 'U' -> {
                int digits = next == 'u' ? 4 : 8;
                int end = at + 2 + digits;
                if (end > last) {
                    throw invalid(key);
                }
                try {
                    out.appendCodePoint(Integer.parseInt(literal.substring(at + 2, end), 16));
                } catch (IllegalArgumentException _) {
                    throw invalid(key);
                }
                return end;
            }
            default -> throw invalid(key);
        }
        return at + 2;
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("Run variable '" + key + "' does not have a valid HCL string value");
    }
}
