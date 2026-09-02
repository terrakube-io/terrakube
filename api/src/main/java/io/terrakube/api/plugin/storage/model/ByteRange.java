package io.terrakube.api.plugin.storage.model;

import lombok.Getter;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single HTTP {@code Range} request, parsed from the header value. Only one range is supported:
 * {@code bytes=START-}, {@code bytes=START-END}, or the suffix form {@code bytes=-SUFFIX}. Anything
 * else (absent, malformed, multi-range) parses to {@link Optional#empty()} and the caller serves the
 * whole object.
 */
@Getter
public final class ByteRange {

    private static final Pattern SINGLE = Pattern.compile("^bytes=(?:(\\d+)-(\\d*)|-(\\d+))$");

    private final long start;        // -1 when this is a suffix range
    private final long end;          // -1 = "to the end of the object"
    private final boolean suffix;
    private final long suffixLength; // -1 when this is not a suffix range

    private ByteRange(long start, long end, boolean suffix, long suffixLength) {
        this.start = start;
        this.end = end;
        this.suffix = suffix;
        this.suffixLength = suffixLength;
    }

    public static Optional<ByteRange> parse(String header) {
        if (header == null || header.isBlank() || header.indexOf(',') >= 0) {
            return Optional.empty();
        }
        Matcher m = SINGLE.matcher(header.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        if (m.group(3) != null) {
            long suffixLength = Long.parseLong(m.group(3));
            return suffixLength == 0 ? Optional.empty()
                    : Optional.of(new ByteRange(-1, -1, true, suffixLength));
        }
        long start = Long.parseLong(m.group(1));
        long end = m.group(2).isEmpty() ? -1 : Long.parseLong(m.group(2));
        if (end >= 0 && end < start) {
            return Optional.empty();
        }
        return Optional.of(new ByteRange(start, end, false, -1));
    }

    /** The value to pass straight to S3 / an HTTP {@code Range} header. */
    public String toHttpHeaderValue() {
        if (suffix) {
            return "bytes=-" + suffixLength;
        }
        return "bytes=" + start + "-" + (end >= 0 ? end : "");
    }
}
