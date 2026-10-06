package io.terrakube.registry.service.inspect;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Block-level scanner for Terraform HCL. It is not an HCL parser: it only finds top-level blocks
 * ({@code variable "x" { ... }}), their labels and the raw text of their top-level attributes.
 * Expressions are never evaluated, which is all the module detail page needs and keeps a full
 * HCL grammar out of the registry. Strings (including {@code ${...}} templates), heredocs and
 * comments are skipped as opaque text so braces inside them do not confuse the nesting.
 */
final class HclBlockScanner {

    // Real modules nest a handful of levels; the cap keeps a crafted file from overflowing the stack.
    private static final int MAX_NESTING_DEPTH = 64;

    record Block(String type, List<String> labels, String body) {
        String label(int index) {
            return index < labels.size() ? labels.get(index) : "";
        }
    }

    private HclBlockScanner() {
    }

    static List<Block> topLevelBlocks(String source) {
        List<Block> blocks = new ArrayList<>();
        parseItems(new Cursor(source), false, new LinkedHashMap<>(), blocks);
        return blocks;
    }

    /** Top-level attributes of a block body, name to raw expression text. Nested blocks are ignored. */
    static Map<String, String> attributes(String body) {
        Map<String, String> attributes = new LinkedHashMap<>();
        parseItems(new Cursor(body), false, attributes, new ArrayList<>());
        return attributes;
    }

    /** Turns a quoted string or heredoc expression into its text; anything else is returned as is. */
    static String unquote(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.startsWith("<<")) {
            String[] lines = raw.split("\n", -1);
            if (lines.length < 2) {
                return "";
            }
            // <<- strips only the smallest common indent, so nested lines keep their relative indentation
            int indent = 0;
            if (raw.startsWith("<<-")) {
                indent = Integer.MAX_VALUE;
                for (int i = 1; i < lines.length - 1; i++) {
                    if (!lines[i].isBlank()) {
                        indent = Math.min(indent, leadingWhitespace(lines[i]));
                    }
                }
            }
            StringBuilder text = new StringBuilder();
            for (int i = 1; i < lines.length - 1; i++) {
                text.append(lines[i].substring(Math.min(indent, leadingWhitespace(lines[i])))).append('\n');
            }
            return text.toString().stripTrailing();
        }
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return unescape(raw.substring(1, raw.length() - 1));
        }
        return raw;
    }

    private static int leadingWhitespace(String line) {
        return line.length() - line.stripLeading().length();
    }

    /** Resolves HCL string escapes in one left-to-right pass; unknown or malformed escapes are kept as written. */
    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 == s.length()) {
                out.append(ch);
                continue;
            }
            char next = s.charAt(i + 1);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case 'u', 'U' -> {
                    int digits = next == 'u' ? 4 : 8;
                    Integer codePoint = hex(s, i + 2, digits);
                    if (codePoint == null) {
                        out.append(ch).append(next);
                    } else {
                        out.appendCodePoint(codePoint);
                        i += digits;
                    }
                }
                default -> out.append(ch).append(next);
            }
            i++;
        }
        return out.toString();
    }

    private static Integer hex(String s, int from, int digits) {
        if (from + digits > s.length()) {
            return null;
        }
        int codePoint = 0;
        for (int i = from; i < from + digits; i++) {
            int digit = Character.digit(s.charAt(i), 16);
            if (digit < 0) {
                return null;
            }
            codePoint = codePoint * 16 + digit;
        }
        return Character.isValidCodePoint(codePoint) ? codePoint : null;
    }

    private static void parseItems(Cursor c, boolean insideBody, Map<String, String> attributes, List<Block> blocks) {
        while (true) {
            c.skipTrivia();
            if (c.eof()) {
                return;
            }
            char ch = c.peek();
            if (ch == '}') {
                c.i++;
                if (insideBody) {
                    return;
                }
                continue;
            }
            if (ch == '"') {
                c.quoted();
                continue;
            }
            String identifier = c.identifier();
            if (identifier.isEmpty()) {
                c.i++;
                continue;
            }
            c.skipTrivia();
            if (!c.eof() && c.peek() == '=') {
                c.i++;
                c.skipTrivia();
                attributes.put(identifier, c.expression());
                continue;
            }
            List<String> labels = new ArrayList<>();
            while (!c.eof()) {
                if (c.peek() == '"') {
                    labels.add(c.quoted());
                } else {
                    String label = c.identifier();
                    if (label.isEmpty()) {
                        break;
                    }
                    labels.add(label);
                }
                c.skipTrivia();
            }
            if (!c.eof() && c.peek() == '{') {
                c.i++;
                int bodyStart = c.i;
                c.enter();
                parseItems(c, true, new LinkedHashMap<>(), new ArrayList<>());
                c.depth--;
                int bodyEnd = c.eof() ? c.s.length() : c.i - 1;
                blocks.add(new Block(identifier, labels, c.s.substring(bodyStart, Math.max(bodyStart, bodyEnd))));
                continue;
            }
            c.skipToEol();
        }
    }

    private static final class Cursor {
        final String s;
        int i;
        int depth;

        Cursor(String s) {
            this.s = s;
        }

        boolean eof() {
            return i >= s.length();
        }

        char peek() {
            return s.charAt(i);
        }

        void enter() {
            if (++depth > MAX_NESTING_DEPTH) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "HCL nesting too deep");
            }
        }

        boolean at(String token) {
            return s.startsWith(token, i);
        }

        void skipTrivia() {
            while (!eof()) {
                char ch = peek();
                if (Character.isWhitespace(ch) || ch == ',') {
                    i++;
                } else if (ch == '#' || at("//")) {
                    skipToEol();
                } else if (at("/*")) {
                    int end = s.indexOf("*/", i + 2);
                    i = end < 0 ? s.length() : end + 2;
                } else {
                    return;
                }
            }
        }

        void skipToEol() {
            int end = s.indexOf('\n', i);
            i = end < 0 ? s.length() : end;
        }

        String identifier() {
            int start = i;
            while (!eof() && (Character.isLetterOrDigit(peek()) || peek() == '_' || peek() == '-')) {
                i++;
            }
            return s.substring(start, i);
        }

        /** At an opening quote. Returns the raw content and leaves the cursor after the closing quote. */
        String quoted() {
            int start = ++i;
            while (!eof()) {
                char ch = peek();
                if (ch == '\\') {
                    i += 2;
                } else if (ch == '"') {
                    String content = s.substring(start, i);
                    i++;
                    return content;
                } else if (at("$${") || at("%%{")) {
                    i += 3;
                } else if (at("${") || at("%{")) {
                    i += 2;
                    enter();
                    expression(true);
                    depth--;
                    if (!eof() && peek() == '}') {
                        i++;
                    }
                } else {
                    i++;
                }
            }
            return s.substring(Math.min(start, s.length()));
        }

        /** At {@code <<}. Leaves the cursor at the end of the closing-marker line. */
        void skipHeredoc() {
            i += 2;
            if (!eof() && peek() == '-') {
                i++;
            }
            int markerStart = i;
            while (!eof() && !Character.isWhitespace(peek())) {
                i++;
            }
            String marker = s.substring(markerStart, i).trim();
            if (marker.length() >= 2 && marker.startsWith("\"") && marker.endsWith("\"")) {
                marker = marker.substring(1, marker.length() - 1);
            }
            skipToEol();
            while (!eof()) {
                i++;
                int lineEnd = s.indexOf('\n', i);
                if (lineEnd < 0) {
                    lineEnd = s.length();
                }
                String line = s.substring(i, lineEnd).trim();
                i = lineEnd;
                if (line.equals(marker)) {
                    return;
                }
            }
        }

        /** Raw expression text up to the end of the line, a closing bracket or a comma at depth zero. */
        String expression() {
            return expression(false);
        }

        // Template expressions end at their closing brace, not at a newline or comma.
        String expression(boolean template) {
            int start = i;
            int depth = 0;
            while (!eof()) {
                char ch = peek();
                if (ch == '"') {
                    quoted();
                } else if (at("<<")) {
                    skipHeredoc();
                } else if (ch == '#' || at("//")) {
                    if (depth == 0 && !template) {
                        break;
                    }
                    skipToEol();
                } else if (at("/*")) {
                    int end = s.indexOf("*/", i + 2);
                    i = end < 0 ? s.length() : end + 2;
                } else if (ch == '{' || ch == '[' || ch == '(') {
                    depth++;
                    i++;
                } else if (ch == '}' || ch == ']' || ch == ')') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                    i++;
                } else if ((ch == '\n' || ch == ',') && depth == 0 && !template) {
                    break;
                } else {
                    i++;
                }
            }
            return s.substring(start, i).trim();
        }
    }
}
