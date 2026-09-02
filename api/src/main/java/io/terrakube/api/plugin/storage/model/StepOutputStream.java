package io.terrakube.api.plugin.storage.model;

import lombok.Getter;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * The result of opening a step-log object for streaming. Never null; use {@link #missing()} when the
 * object does not exist. A {@code partial} result carries the RFC 7233 {@code Content-Range} for the
 * bytes actually returned.
 */
@Getter
public final class StepOutputStream implements Closeable {

    private final InputStream content;   // null when !exists
    private final long contentLength;    // bytes available in `content`, -1 when unknown
    private final long totalLength;      // full object size, -1 when unknown
    private final String contentRange;   // "bytes start-end/total", null when not partial
    private final boolean exists;
    private final boolean partial;

    private StepOutputStream(InputStream content, long contentLength, long totalLength,
                             String contentRange, boolean exists, boolean partial) {
        this.content = content;
        this.contentLength = contentLength;
        this.totalLength = totalLength;
        this.contentRange = contentRange;
        this.exists = exists;
        this.partial = partial;
    }

    public static StepOutputStream of(InputStream content, long contentLength, long totalLength) {
        return new StepOutputStream(content, contentLength, totalLength, null, true, false);
    }

    public static StepOutputStream partial(InputStream content, long contentLength,
                                           String contentRange, long totalLength) {
        return new StepOutputStream(content, contentLength, totalLength, contentRange, true, true);
    }

    public static StepOutputStream missing() {
        return new StepOutputStream(null, -1L, -1L, null, false, false);
    }

    @Override
    public void close() throws IOException {
        if (content != null) {
            content.close();
        }
    }
}
