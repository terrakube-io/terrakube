package io.terrakube.api.plugin.streaming;

import org.springframework.data.redis.connection.stream.RecordId;

/** Ordering of Redis stream record ids ({@code <millis>-<sequence>}). */
final class StreamIds {

    private StreamIds() {
    }

    static int compare(RecordId a, RecordId b) {
        int byTimestamp = Long.compare(a.getTimestamp(), b.getTimestamp());
        return byTimestamp != 0 ? byTimestamp : Long.compare(a.getSequence(), b.getSequence());
    }

    static boolean isZero(RecordId id) {
        return id.getTimestamp() == 0 && id.getSequence() == 0;
    }
}
