package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplyJsonEventParserTest {

    private ApplyJsonEventParser subject() {
        return new ApplyJsonEventParser(new ObjectMapper());
    }

    private List<Map<String, Object>> oneChange(String address) {
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, Object> change = new HashMap<>();
        change.put("address", address);
        change.put("status", "pending");
        changes.add(change);
        return changes;
    }

    @Test
    void marksResourceApplyingOnApplyStart() {
        List<Map<String, Object>> changes = oneChange("aws_instance.foo");

        String message = subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Creating...\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"action\":\"create\"},\"type\":\"apply_start\"}",
                changes);

        assertEquals("applying", changes.get(0).get("status"));
        assertEquals("aws_instance.foo: Creating...", message);
    }

    @Test
    void marksResourceAppliedOnApplyComplete() {
        List<Map<String, Object>> changes = oneChange("aws_instance.foo");

        subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Creating...\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"action\":\"create\"},\"type\":\"apply_start\"}",
                changes);
        subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Creation complete after 0s [id=abc]\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"action\":\"create\",\"id_key\":\"id\",\"id_value\":\"abc\",\"elapsed_seconds\":0},\"type\":\"apply_complete\"}",
                changes);

        assertEquals("applied", changes.get(0).get("status"));
    }

    @Test
    void marksResourceErroredAndAttachesDiagnosticMessage() {
        List<Map<String, Object>> changes = oneChange("null_resource.fails");

        subject().parseLine(
                "{\"@message\":\"null_resource.fails: Creation errored after 0s\",\"hook\":{\"resource\":{\"addr\":\"null_resource.fails\"},\"action\":\"create\",\"elapsed_seconds\":0},\"type\":\"apply_errored\"}",
                changes);
        subject().parseLine(
                "{\"@message\":\"Error: local-exec provisioner error\",\"diagnostic\":{\"severity\":\"error\",\"summary\":\"local-exec provisioner error\",\"detail\":\"Error running command\",\"address\":\"null_resource.fails\"},\"type\":\"diagnostic\"}",
                changes);

        assertEquals("errored", changes.get(0).get("status"));
        assertEquals("local-exec provisioner error", changes.get(0).get("error"));
    }

    @Test
    void returnsMessageForNonHookEventsAndOriginalLineForUnparsableLines() {
        List<Map<String, Object>> changes = oneChange("aws_instance.foo");

        String message = subject().parseLine(
                "{\"@message\":\"Apply complete! Resources: 1 added, 0 changed, 0 destroyed.\",\"type\":\"change_summary\"}",
                changes);

        assertEquals("Apply complete! Resources: 1 added, 0 changed, 0 destroyed.", message);
        assertEquals("not valid json", subject().parseLine("not valid json", changes));
    }
}
