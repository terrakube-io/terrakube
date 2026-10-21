package io.terrakube.api.plugin.logs;

import java.nio.charset.StandardCharsets;

/**
 * The line as the live stream carries it. The executor already cuts a line over its own limit, this
 * covers an executor that does not, so one event cannot make the Redis stream unbounded in bytes.
 */
final class LogLine {

    private LogLine() {
    }

    /** @param maxBytes the most bytes (UTF-8) of the line to keep; 0 or less keeps it whole. */
    static String cap(String line, int maxBytes) {
        if (line == null || maxBytes <= 0 || (long) line.length() * 3 <= maxBytes) {
            return line;
        }
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return line;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8)
                + " ... [" + (bytes.length - end) + " bytes left out of the live view, the saved output has the whole line]";
    }

    static long bytesOf(String line) {
        return line == null ? 0 : line.getBytes(StandardCharsets.UTF_8).length;
    }
}
