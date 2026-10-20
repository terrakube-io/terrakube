package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-entry ordering for job context writes. The document carries a top-level
 * {@code contextRevisions} map from entry path ({@code key} or {@code key/child}) to the highest
 * revision applied for it, so a retried, duplicated or reordered write can never replace newer
 * data. A deleted entry keeps its revision as a tombstone.
 */
public final class ContextPatchApplier {

    public static final String REVISIONS_KEY = "contextRevisions";

    public static final int MAX_ENTRIES = 64;

    // A revision is executionEpoch * REVISION_EPOCH_SCALE + seq. The epoch is the API's own clock
    // when it dispatched the step (ExecutorContext.executionEpoch) and seq counts up inside that
    // execution, so a re-dispatched step always outranks the execution it replaces.
    public static final long REVISION_EPOCH_SCALE = 1_000_000L;

    // The epoch is minted by an API pod, so this only covers clock skew between API pods. An epoch
    // further ahead is forged and must not outrank the next dispatched execution for long.
    // Executions are only ordered correctly while API pod clocks agree: a re-dispatch minted on a
    // pod whose clock lags the previous dispatcher's by more than the gap between them loses.
    private static final long MAX_CLOCK_SKEW_MILLIS = 10_000;

    public record Patch(long revision, ObjectNode set) {
    }

    private ContextPatchApplier() {
    }

    /**
     * Validates a {@code {"revision": n, "set": {path: value|null}}} body.
     *
     * @throws InvalidContextPatchException for an invalid revision, set or path.
     */
    public static Patch parse(JsonNode body, long nowMillis) {
        JsonNode revision = body.get("revision");
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()
                || revision.asLong() <= 0 || revision.asLong() / REVISION_EPOCH_SCALE > nowMillis + MAX_CLOCK_SKEW_MILLIS) {
            throw new InvalidContextPatchException("revision must be a positive long that is not in the future");
        }
        if (!(body.get("set") instanceof ObjectNode set) || set.isEmpty() || set.size() > MAX_ENTRIES) {
            throw new InvalidContextPatchException("set must be an object with 1 to " + MAX_ENTRIES + " entries");
        }
        for (Map.Entry<String, JsonNode> entry : set.properties()) {
            String[] segments = entry.getKey().split("/", -1);
            if (segments.length > 2 || Arrays.asList(segments).contains("") || REVISIONS_KEY.equals(segments[0])) {
                throw new InvalidContextPatchException("set contains an invalid path");
            }
        }
        return new Patch(revision.asLong(), set);
    }

    /**
     * Applies each entry of a {@link #parse parsed} patch that is newer than what is stored, in place.
     * {@code key/child} is ordered against both its own revision and {@code key}'s; {@code key} against
     * its own and every {@code key/*} revision, which it then subsumes.
     *
     * @return the applied paths; the other entries were stale and left untouched.
     */
    public static List<String> apply(ObjectNode stored, Patch patch) {
        ObjectNode revisions = stored.get(REVISIONS_KEY) instanceof ObjectNode existing ? existing : stored.putObject(REVISIONS_KEY);
        Map<String, Set<String>> children = new HashMap<>();
        Map<String, Long> maxChildRevision = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : revisions.properties()) {
            int slash = entry.getKey().indexOf('/');
            if (slash > 0) {
                String key = entry.getKey().substring(0, slash);
                children.computeIfAbsent(key, k -> new HashSet<>()).add(entry.getKey());
                maxChildRevision.merge(key, entry.getValue().asLong(), Math::max);
            }
        }

        long revision = patch.revision();
        List<String> applied = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : patch.set().properties()) {
            String path = entry.getKey();
            int slash = path.indexOf('/');
            String key = slash < 0 ? path : path.substring(0, slash);
            long current = slash < 0
                    ? Math.max(revisions.path(path).asLong(), maxChildRevision.getOrDefault(path, 0L))
                    : Math.max(revisions.path(path).asLong(), revisions.path(key).asLong());
            if (revision <= current) {
                continue;
            }
            write(stored, path, entry.getValue());
            revisions.put(path, revision);
            if (slash < 0) {
                Set<String> subsumed = children.remove(path);
                if (subsumed != null) {
                    revisions.remove(subsumed);
                }
                maxChildRevision.remove(path);
            } else {
                children.computeIfAbsent(key, k -> new HashSet<>()).add(path);
                maxChildRevision.merge(key, revision, Math::max);
            }
            applied.add(path);
        }
        return applied;
    }

    /**
     * For a full-document replace: revisioned entries only change through a patch. The incoming
     * revisions are dropped, and every stored revisioned entry keeps its stored value (or stays
     * deleted) along with its revision.
     */
    public static void protectRevisions(ObjectNode stored, ObjectNode incoming) {
        incoming.remove(REVISIONS_KEY);
        if (!(stored.get(REVISIONS_KEY) instanceof ObjectNode storedRevisions)) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : storedRevisions.properties()) {
            write(incoming, entry.getKey(), read(stored, entry.getKey()));
        }
        incoming.set(REVISIONS_KEY, storedRevisions.deepCopy());
    }

    private static JsonNode read(ObjectNode document, String path) {
        int slash = path.indexOf('/');
        if (slash < 0) {
            return document.get(path);
        }
        return document.get(path.substring(0, slash)) instanceof ObjectNode parent ? parent.get(path.substring(slash + 1)) : null;
    }

    /** A {@code null} value deletes the entry. */
    private static void write(ObjectNode document, String path, JsonNode value) {
        boolean delete = value == null || value.isNull();
        int slash = path.indexOf('/');
        if (slash < 0) {
            if (delete) {
                document.remove(path);
            } else {
                document.set(path, value);
            }
            return;
        }
        String key = path.substring(0, slash);
        String child = path.substring(slash + 1);
        if (delete) {
            if (document.get(key) instanceof ObjectNode parent) {
                parent.remove(child);
            }
            return;
        }
        ObjectNode parent = document.get(key) instanceof ObjectNode existing ? existing : document.putObject(key);
        parent.set(child, value);
    }
}
