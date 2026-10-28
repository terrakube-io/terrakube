package io.terrakube.api.plugin.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the {@code variables} a Terraform or OpenTofu CLI sends when it creates a run through the Terraform
 * Cloud compatible Runs API.
 *
 * <p>The CLI collects the values of {@code -var}, {@code -var-file} and {@code TF_VAR_} inputs and sends each as
 * {@code {"key": "name", "value": "<HCL literal>"}}. As in the Terraform Cloud API, every value is an HCL
 * literal: a string arrives quoted ({@code "\"from-file\""}), while numbers, booleans, lists and objects arrive as
 * written in HCL ({@code 5}, {@code true}, {@code ["a", "b"]}).
 *
 * <p>The literals are validated here and kept as received. At dispatch a string literal becomes the value of a
 * {@code --var name=value} argument, where Terraform parses it by the declared type of the variable. Every other
 * literal also goes to a variable file ({@link #isString}), because Terraform reads {@code --var} values of a
 * variable without a type constraint as strings, while a variable file keeps the list, object or number.
 */
public final class CliRunVariables {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private CliRunVariables() {
    }

    /**
     * @param attribute the {@code data.attributes.variables} value of the create run request, or null
     * @return each variable name with its HCL literal, in request order. A variable whose value is the literal
     *         {@code null} is left out, so the workspace value (or the default) applies.
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
            String literal = value.strip();
            if (literal.isEmpty()) {
                throw new IllegalArgumentException("Run variable '" + key + "' has an empty value");
            }
            if (!"null".equals(literal)) {
                String decoded = new Reader(key, literal).readDocument();
                if (decoded.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("Run variable '" + key + "' must not contain NUL characters");
                }
                variables.put(key, literal);
            }
        }
        return variables;
    }

    /** True for a quoted string or a heredoc, the literals whose value is plain text. */
    public static boolean isString(String literal) {
        return literal.charAt(0) == '"' || literal.startsWith("<<");
    }

    /**
     * The text for {@code --var name=value}: the decoded string for a string literal, and the literal itself for
     * any other (Terraform parses it by the declared type).
     */
    public static String argumentValue(String name, String literal) {
        if (!isString(literal)) {
            return literal;
        }
        return new Reader(name, literal).readDocument();
    }

    /** A recursive reader of the HCL literal forms: strings, heredocs, numbers, booleans, null, tuples, objects. */
    private static final class Reader {
        private final String name;
        private final String text;
        private int at;

        Reader(String name, String text) {
            this.name = name;
            this.text = text;
        }

        /** Reads the whole text as one literal. Returns the decoded value for a string, else the text. */
        String readDocument() {
            String decoded;
            if (text.startsWith("<<")) {
                decoded = heredoc();
            } else {
                decoded = value(true);
                skipSpace();
            }
            if (at != text.length()) {
                throw invalid();
            }
            return decoded;
        }

        private String value(boolean top) {
            skipSpace();
            if (at >= text.length()) {
                throw invalid();
            }
            char c = text.charAt(at);
            if (c == '"') {
                return quoted();
            }
            if (c == '[') {
                tuple();
                return top ? text : null;
            }
            if (c == '{') {
                object();
                return top ? text : null;
            }
            if (c == '-' || Character.isDigit(c)) {
                number();
                return top ? text : null;
            }
            for (String word : new String[]{"true", "false", "null"}) {
                if (text.startsWith(word, at) && !identifierChar(at + word.length())) {
                    at += word.length();
                    return top ? text : null;
                }
            }
            throw invalid();
        }

        private boolean identifierChar(int index) {
            return index < text.length() && (Character.isLetterOrDigit(text.charAt(index))
                    || text.charAt(index) == '_' || text.charAt(index) == '-');
        }

        private void number() {
            java.util.regex.Matcher matcher = NUMBER.matcher(text).region(at, text.length());
            if (!matcher.lookingAt() || identifierChar(matcher.end())) {
                throw invalid();
            }
            at = matcher.end();
        }

        private void tuple() {
            at++;
            boolean expectValue = true;
            while (true) {
                skipSpaceAndNewlines();
                if (at >= text.length()) {
                    throw invalid();
                }
                if (text.charAt(at) == ']') {
                    at++;
                    return;
                }
                if (!expectValue) {
                    throw invalid();
                }
                value(false);
                expectValue = separator();
            }
        }

        private void object() {
            at++;
            boolean expectEntry = true;
            while (true) {
                skipSpaceAndNewlines();
                if (at >= text.length()) {
                    throw invalid();
                }
                if (text.charAt(at) == '}') {
                    at++;
                    return;
                }
                if (!expectEntry) {
                    throw invalid();
                }
                key();
                skipSpace();
                if (at >= text.length() || (text.charAt(at) != '=' && text.charAt(at) != ':')) {
                    throw invalid();
                }
                at++;
                value(false);
                expectEntry = separator();
            }
        }

        /** Consumes the comma or newline after an element. Returns false when there was none. */
        private boolean separator() {
            skipSpace();
            if (at < text.length() && text.charAt(at) == ',') {
                at++;
                return true;
            }
            return at < text.length() && (text.charAt(at) == '\n' || text.charAt(at) == '\r');
        }

        private void key() {
            if (at < text.length() && text.charAt(at) == '"') {
                quoted();
                return;
            }
            java.util.regex.Matcher matcher = IDENTIFIER.matcher(text).region(at, text.length());
            if (!matcher.lookingAt()) {
                throw invalid();
            }
            at = matcher.end();
        }

        /** Reads a quoted string at {@code at} and returns its decoded value. */
        private String quoted() {
            StringBuilder out = new StringBuilder();
            int i = at + 1;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '"') {
                    at = i + 1;
                    return out.toString();
                }
                if (c == '\n' || c == '\r') {
                    throw invalid();
                }
                if (c == '\\') {
                    i = escape(i, out);
                } else {
                    i = templateCharacter(text, i, out);
                }
            }
            throw invalid();
        }

        /** Reads one character of a template literal, decoding {@code $${} and {@code %%{}. */
        private int templateCharacter(String source, int i, StringBuilder out) {
            char c = source.charAt(i);
            if ((c == '$' || c == '%') && i + 2 < source.length() && source.charAt(i + 1) == c && source.charAt(i + 2) == '{') {
                out.append(c).append('{');
                return i + 3;
            }
            if ((c == '$' || c == '%') && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                // A real interpolation or directive: a literal never contains one.
                throw invalid();
            }
            out.append(c);
            return i + 1;
        }

        private int escape(int i, StringBuilder out) {
            if (i + 1 >= text.length()) {
                throw invalid();
            }
            char next = text.charAt(i + 1);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case 'u', 'U' -> {
                    int digits = next == 'u' ? 4 : 8;
                    int end = i + 2 + digits;
                    if (end > text.length()) {
                        throw invalid();
                    }
                    try {
                        out.appendCodePoint(Integer.parseInt(text.substring(i + 2, end), 16));
                    } catch (IllegalArgumentException _) {
                        throw invalid();
                    }
                    return end;
                }
                default -> throw invalid();
            }
            return i + 2;
        }

        /**
         * A heredoc: {@code <<MARKER} or {@code <<-MARKER}, the lines, and the marker on a line of its own. The
         * value ends with a newline after the last line, and {@code <<-} removes the smallest indentation of the
         * lines. Like a quoted string it has no backslash escapes, and {@code ${} and {@code %{} are not allowed.
         */
        private String heredoc() {
            int i = 2;
            boolean indented = i < text.length() && text.charAt(i) == '-';
            if (indented) {
                i++;
            }
            int markerStart = i;
            while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                i++;
            }
            String marker = text.substring(markerStart, i);
            if (marker.isEmpty() || Character.isDigit(marker.charAt(0))) {
                throw invalid();
            }
            if (i < text.length() && text.charAt(i) == '\r') {
                i++;
            }
            if (i >= text.length() || text.charAt(i) != '\n') {
                throw invalid();
            }
            i++;
            List<String> lines = new ArrayList<>();
            while (true) {
                if (i > text.length()) {
                    throw invalid();
                }
                int end = text.indexOf('\n', i);
                boolean last = end < 0;
                String line = text.substring(i, last ? text.length() : end);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                String candidate = indented ? line.stripLeading() : line;
                if (candidate.equals(marker)) {
                    at = last ? text.length() : end + 1;
                    skipSpaceAndNewlines();
                    break;
                }
                if (last) {
                    throw invalid();
                }
                lines.add(line);
                i = end + 1;
            }
            if (indented) {
                lines = removeIndentation(lines);
            }
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                int j = 0;
                while (j < line.length()) {
                    j = templateCharacter(line, j, out);
                }
                out.append('\n');
            }
            return out.toString();
        }

        private List<String> removeIndentation(List<String> lines) {
            int min = Integer.MAX_VALUE;
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                int spaces = 0;
                while (spaces < line.length() && line.charAt(spaces) == ' ') {
                    spaces++;
                }
                min = Math.min(min, spaces);
            }
            if (min == Integer.MAX_VALUE) {
                min = 0;
            }
            List<String> stripped = new ArrayList<>();
            for (String line : lines) {
                int remove = 0;
                while (remove < min && remove < line.length() && line.charAt(remove) == ' ') {
                    remove++;
                }
                stripped.add(line.substring(remove));
            }
            return stripped;
        }

        private void skipSpace() {
            while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) {
                at++;
            }
        }

        private void skipSpaceAndNewlines() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Run variable '" + name + "' does not have a valid HCL literal value");
        }
    }
}
