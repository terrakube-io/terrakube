package io.terrakube.api.plugin.storage;

import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputPart;
import io.terrakube.api.plugin.storage.model.StepOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the parts of a step's output as one stream. The executor archives the output of a running step
 * as {@code <step>.parts/000001.part}, {@code 000002.part} and so on. The step's final object replaces them
 * when the step ends; this is what is read when the step ended without one. It sits on two primitives
 * every storage backend has ({@link StorageTypeService#listStepOutputParts} and
 * {@link StorageTypeService#openStepOutputPart}), so the joining and the range arithmetic exist once.
 */
public final class StepOutputPartsReader {

    private static final Pattern PART_NAME = Pattern.compile("^(\\d{6})\\.part$");

    private StepOutputPartsReader() {
    }

    /**
     * The parts that make up the output: numbered 1, 2, 3 ... in order, up to the first one that is missing.
     * Anything after a gap, or not named like a part, is left out, because the bytes after a hole are not
     * the continuation of what came before.
     */
    public static List<StepOutputPart> contiguous(List<StepOutputPart> listed) {
        List<StepOutputPart> sorted = new ArrayList<>(listed == null ? List.of() : listed);
        sorted.sort(Comparator.comparing(StepOutputPart::name));
        List<StepOutputPart> result = new ArrayList<>();
        for (StepOutputPart part : sorted) {
            var matcher = PART_NAME.matcher(part.name());
            if (!matcher.matches() || Integer.parseInt(matcher.group(1)) != result.size() + 1) {
                break;
            }
            result.add(part);
        }
        return result;
    }

    /** Part names are generated, never taken from a request: refuse anything else before it becomes part of a storage key. */
    public static void requirePartName(String partName) throws IOException {
        if (partName == null || !PART_NAME.matcher(partName).matches()) {
            throw new IOException("Not an output part name: " + partName);
        }
    }

    public static long totalSize(List<StepOutputPart> parts) {
        long total = 0;
        for (StepOutputPart part : parts) {
            total += part.size();
        }
        return total;
    }

    /** The joined output (or the requested range of it), or {@link StepOutputStream#missing()} when there are no parts. */
    public static StepOutputStream open(StorageTypeService storage, String organizationId, String jobId, String stepId, ByteRange range) {
        return open(storage, organizationId, jobId, stepId, range, contiguous(storage.listStepOutputParts(organizationId, jobId, stepId)));
    }

    /** Like {@link #open(StorageTypeService, String, String, String, ByteRange)} for parts the caller has already listed, so a request lists them once. */
    public static StepOutputStream open(StorageTypeService storage, String organizationId, String jobId, String stepId, ByteRange range, List<StepOutputPart> parts) {
        long total = totalSize(parts);
        if (parts.isEmpty() || total == 0) {
            return StepOutputStream.missing();
        }
        long start = 0;
        long endInclusive = total - 1;
        if (range != null) {
            if (range.isSuffix()) {
                start = Math.max(0, total - range.getSuffixLength());
            } else {
                start = range.getStart();
                if (range.getEnd() >= 0) {
                    endInclusive = Math.min(range.getEnd(), total - 1);
                }
            }
            if (start >= total) {
                return StepOutputStream.missing();
            }
        }
        long length = endInclusive - start + 1;
        InputStream joined = new JoinedParts(storage, organizationId, jobId, stepId, segments(parts, start, endInclusive));
        if (range == null) {
            return StepOutputStream.of(joined, length, total);
        }
        return StepOutputStream.partial(joined, length, "bytes " + start + "-" + endInclusive + "/" + total, total);
    }

    /** Maps a byte range of the whole output onto the parts it touches. */
    static List<Segment> segments(List<StepOutputPart> parts, long start, long endInclusive) {
        List<Segment> segments = new ArrayList<>();
        long partStart = 0;
        for (StepOutputPart part : parts) {
            long partEnd = partStart + part.size() - 1;
            if (partEnd >= start && partStart <= endInclusive) {
                long from = Math.max(start, partStart) - partStart;
                long to = Math.min(endInclusive, partEnd) - partStart;
                segments.add(new Segment(part.name(), from, to - from + 1));
            }
            partStart += part.size();
            if (partStart > endInclusive) {
                break;
            }
        }
        return segments;
    }

    record Segment(String partName, long offset, long length) {
    }

    /** Opens each part only when the previous one is read to the end, so one part is open at a time. */
    private static final class JoinedParts extends InputStream {
        private final StorageTypeService storage;
        private final String organizationId;
        private final String jobId;
        private final String stepId;
        private final List<Segment> segments;
        private int next;
        private InputStream current;

        JoinedParts(StorageTypeService storage, String organizationId, String jobId, String stepId, List<Segment> segments) {
            this.storage = storage;
            this.organizationId = organizationId;
            this.jobId = jobId;
            this.stepId = stepId;
            this.segments = segments;
        }

        private boolean advance() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
            if (next >= segments.size()) {
                return false;
            }
            Segment segment = segments.get(next++);
            current = storage.openStepOutputPart(organizationId, jobId, stepId, segment.partName(), segment.offset(), segment.length());
            return true;
        }

        private final byte[] single = new byte[1];

        @Override
        public int read() throws IOException {
            int n = read(single, 0, 1);
            return n < 0 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (true) {
                if (current == null && !advance()) {
                    return -1;
                }
                int n = current.read(buffer, offset, length);
                if (n >= 0) {
                    return n;
                }
                if (!advance()) {
                    return -1;
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
            next = segments.size();
        }
    }
}
