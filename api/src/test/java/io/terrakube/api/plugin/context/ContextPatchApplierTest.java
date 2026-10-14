package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextPatchApplierTest {

    private static final long NOW = 1_760_000_000_000L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ObjectNode json(String json) throws Exception {
        return (ObjectNode) objectMapper.readTree(json);
    }

    /** Re-parsed, so stored long revisions compare equal to the int literals of the expected JSON. */
    private ObjectNode reparsed(ObjectNode node) throws Exception {
        return json(node.toString());
    }

    private List<String> apply(ObjectNode stored, long revision, String set) throws Exception {
        return ContextPatchApplier.apply(stored, ContextPatchApplier.parse(
                json("{\"revision\":" + revision + ",\"set\":" + set + "}"), NOW));
    }

    @Test
    void reorderedPatchesKeepTheNewestValuePerEntry() throws Exception {
        ObjectNode stored = json("{}");

        apply(stored, 2, "{\"terrakubeUI/s1\":\"new\",\"jobDiagnostics/s1\":[1]}");
        List<String> applied = apply(stored, 1, "{\"terrakubeUI/s1\":\"old\",\"planStructuredOutput/s1\":[]}");

        assertEquals(List.of("planStructuredOutput/s1"), applied);
        assertEquals(json("""
                {"contextRevisions":{"terrakubeUI/s1":2,"jobDiagnostics/s1":2,"planStructuredOutput/s1":1},
                 "terrakubeUI":{"s1":"new"},"jobDiagnostics":{"s1":[1]},"planStructuredOutput":{"s1":[]}}
                """), reparsed(stored));
    }

    @Test
    void duplicatePatchAppliesNothing() throws Exception {
        ObjectNode stored = json("{}");
        apply(stored, 5, "{\"structuredOutputStatus\":{\"state\":\"COMPLETE\"}}");
        ObjectNode before = stored.deepCopy();

        assertEquals(List.of(), apply(stored, 5, "{\"structuredOutputStatus\":{\"state\":\"COMPLETE\"}}"));
        assertEquals(before, stored);
    }

    @Test
    void finalSnapshotIsNotReplacedByAnOlderProgressSnapshot() throws Exception {
        ObjectNode stored = json("{}");
        apply(stored, 9, "{\"structuredOutputStatus\":{\"state\":\"COMPLETE\"}}");

        apply(stored, 4, "{\"structuredOutputStatus\":{\"state\":\"RUNNING\"}}");

        assertEquals("COMPLETE", stored.at("/structuredOutputStatus/state").asText());
    }

    @Test
    void olderExecutionsFinalSnapshotLosesToANewerExecutionsProgress() throws Exception {
        long scale = ContextPatchApplier.REVISION_EPOCH_SCALE;
        ObjectNode stored = json("{}");
        // The step was re-dispatched a second later; the new execution's first snapshot lands first.
        apply(stored, (NOW + 1_000) * scale + 1, "{\"structuredOutputStatus\":{\"state\":\"RUNNING\"}}");

        // The old execution's final snapshot, however late in its own sequence, is stale.
        assertEquals(List.of(), apply(stored, NOW * scale + 999, "{\"structuredOutputStatus\":{\"state\":\"COMPLETE\"}}"));
        assertEquals("RUNNING", stored.at("/structuredOutputStatus/state").asText());
    }

    @Test
    void nullDeletesTheEntryAndKeepsItsRevisionAsATombstone() throws Exception {
        ObjectNode stored = json("{\"noChangePlan\":{\"planStepId\":\"s1\"},\"terrakubeUI\":{\"s1\":\"ui\",\"s2\":\"ui\"}}");

        apply(stored, 3, "{\"noChangePlan\":null,\"terrakubeUI/s1\":null}");
        apply(stored, 2, "{\"noChangePlan\":{\"planStepId\":\"s1\"},\"terrakubeUI/s1\":\"stale\"}");

        assertEquals(json("{\"terrakubeUI\":{\"s2\":\"ui\"},\"contextRevisions\":{\"noChangePlan\":3,\"terrakubeUI/s1\":3}}"), reparsed(stored));
    }

    @Test
    void childSegmentsAreStoredVerbatim() throws Exception {
        ObjectNode stored = json("{\"planStructuredOutput\":\"not-an-object\"}");

        apply(stored, 1, "{\"planStructuredOutput/a.b~1[0]\":[]}");

        assertEquals(json("{\"planStructuredOutput\":{\"a.b~1[0]\":[]},\"contextRevisions\":{\"planStructuredOutput/a.b~1[0]\":1}}"), reparsed(stored));
    }

    @Test
    void childIsOrderedAgainstItsParentRevision() throws Exception {
        ObjectNode stored = json("{}");
        apply(stored, 5, "{\"terrakubeUI\":{\"s1\":\"whole\"}}");

        assertEquals(List.of(), apply(stored, 4, "{\"terrakubeUI/s2\":\"older\"}"));
        assertEquals(List.of("terrakubeUI/s2"), apply(stored, 6, "{\"terrakubeUI/s2\":\"newer\"}"));

        assertEquals(json("""
                {"terrakubeUI":{"s1":"whole","s2":"newer"},"contextRevisions":{"terrakubeUI":5,"terrakubeUI/s2":6}}
                """), reparsed(stored));
    }

    @Test
    void parentIsOrderedAgainstItsNewestChildAndSubsumesChildren() throws Exception {
        ObjectNode stored = json("{}");
        apply(stored, 3, "{\"terrakubeUI/s1\":\"a\"}");
        apply(stored, 7, "{\"terrakubeUI/s2\":\"b\",\"jobDiagnostics/s1\":[]}");

        assertEquals(List.of(), apply(stored, 6, "{\"terrakubeUI\":{}}"));
        assertEquals(List.of("terrakubeUI"), apply(stored, 8, "{\"terrakubeUI\":{\"s9\":\"c\"}}"));
        // The subsumed child revisions are gone: a child is now ordered against the parent's 8.
        assertEquals(List.of(), apply(stored, 7, "{\"terrakubeUI/s1\":\"stale\"}"));

        assertEquals(json("""
                {"terrakubeUI":{"s9":"c"},"jobDiagnostics":{"s1":[]},
                 "contextRevisions":{"jobDiagnostics/s1":7,"terrakubeUI":8}}
                """), reparsed(stored));
    }

    @Test
    void parseRejectsForgedRevisionsOversizedSetsAndBadPaths() throws Exception {
        // The epoch may be at most 10s ahead of this API's clock, whatever its seq.
        long latest = (NOW + 10_000) * ContextPatchApplier.REVISION_EPOCH_SCALE + 999_999;
        long future = (NOW + 10_001) * ContextPatchApplier.REVISION_EPOCH_SCALE;
        assertEquals(latest, ContextPatchApplier.parse(json("{\"revision\":" + latest + ",\"set\":{\"a\":1}}"), NOW).revision());

        for (String body : new String[]{
                "{\"revision\":" + future + ",\"set\":{\"a\":1}}",
                "{\"revision\":-1,\"set\":{\"a\":1}}",
                "{\"revision\":1,\"set\":{\"a//b\":1}}",
                "{\"revision\":1,\"set\":{\"contextRevisions/a\":1}}"}) {
            assertThrows(InvalidContextPatchException.class, () -> ContextPatchApplier.parse(json(body), NOW), body);
        }
        ObjectNode oversized = json("{\"revision\":1}");
        ObjectNode set = oversized.putObject("set");
        for (int i = 0; i <= ContextPatchApplier.MAX_ENTRIES; i++) {
            set.put("terraformOutputs/s" + i, i);
        }
        assertThrows(InvalidContextPatchException.class, () -> ContextPatchApplier.parse(oversized, NOW));
    }

    @Test
    void fullReplaceCannotChangeRevisionedEntries() throws Exception {
        ObjectNode stored = json("{\"custom\":0}");
        apply(stored, 5, "{\"planRenderedText/s1\":\"text\",\"noChangePlan\":null,\"terrakubeUI/s1\":\"v5\"}");
        // Built from a read taken before the patch, with its own changes and a forged, mixed-level revision map.
        ObjectNode incoming = json("""
                {"custom":1,"noChangePlan":{"planStepId":"s1"},"terrakubeUI":{"s1":"script","s2":"own"},
                 "contextRevisions":{"terrakubeUI/s1":9000000000000000,"terrakubeUI":9000000000000000,"custom":9000000000000000}}
                """);

        ContextPatchApplier.protectRevisions(stored, incoming);

        assertEquals(json("""
                {"custom":1,"terrakubeUI":{"s1":"v5","s2":"own"},"planRenderedText":{"s1":"text"},
                 "contextRevisions":{"planRenderedText/s1":5,"noChangePlan":5,"terrakubeUI/s1":5}}
                """), reparsed(incoming));
    }

    @Test
    void fullReplaceDropsClientRevisionsWhenNoneAreStored() throws Exception {
        ObjectNode incoming = json("{\"a\":1,\"contextRevisions\":{\"a\":9000000000000000}}");

        ContextPatchApplier.protectRevisions(json("{\"a\":0}"), incoming);

        assertEquals(json("{\"a\":1}"), incoming);
    }
}
