package io.terrakube.executor.service.logs;

import java.nio.charset.StandardCharsets;

/**
 * The line as the live stream carries it. A single event of any size would make the byte size of the
 * stream unbounded, so a line over the limit is cut and says how much was left out. The saved output
 * is built from the whole line and is not affected.
 */
public final class LiveLogLine {

    private LiveLogLine() {
    }

    /**
     * @param maxBytes the most bytes of the line to keep (UTF-8); 0 or less keeps it whole.
     */
    public static String cap(String line, int maxBytes) {
        if (line == null || maxBytes <= 0) {
            return line;
        }
        // A character takes at most 3 bytes in a Java string's UTF-8, so this is a cheap way to skip
        // the encoding for every ordinary line.
        if ((long) line.length() * 3 <= maxBytes) {
            return line;
        }
        if (utf8Length(line) <= maxBytes) {
            return line;
        }
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        int end = maxBytes;
        // Do not cut in the middle of a multi-byte character (continuation bytes are 10xxxxxx).
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        int omitted = bytes.length - end;
        return new String(bytes, 0, end, StandardCharsets.UTF_8)
                + " ... [" + omitted + " bytes left out of the live view, the saved output has the whole line]";
    }

    /** The size of the line in UTF-8 bytes, without encoding it into a new array. */
    public static long utf8Length(CharSequence text) {
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
