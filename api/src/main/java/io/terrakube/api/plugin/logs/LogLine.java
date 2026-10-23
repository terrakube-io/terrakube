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
        if (bytesOf(line) <= maxBytes) {
            return line;
        }
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8)
                + " ... [" + (bytes.length - end) + " bytes left out of the live view, the saved output has the whole line]";
    }

    /** What a line costs in a request besides its text: the object, the ids and the line number. */
    static final int LINE_OVERHEAD_BYTES = 96;

    /** The size of the line in UTF-8 bytes, without encoding it into a new array. */
    static long bytesOf(String text) {
        if (text == null) {
            return 0;
        }
        long bytes = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                bytes += 1; // a lone surrogate is encoded as '?'
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
