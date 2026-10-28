package io.terrakube.api.plugin.execution;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CliRunVariablesTest {
    private static Map<String, Object> pair(String key, String value) {
        return Map.of("key", key, "value", value);
    }

    private static Map<String, String> parse(Map<String, Object>... pairs) {
        return CliRunVariables.parse(List.of(pairs));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotedStringsArriveAsTheirRawValueAndOtherLiteralsStayHcl() {
        Map<String, String> variables = parse(
                pair("example", "\"from-file\""),
                pair("count", "5"),
                pair("enabled", "true"),
                pair("names", "[\"a\", \"b\"]"),
                pair("tags", "{\n  env = \"prod\"\n}"),
                pair("empty", "\"\""));
        assertEquals("from-file", variables.get("example"));
        assertEquals("5", variables.get("count"));
        assertEquals("true", variables.get("enabled"));
        assertEquals("[\"a\", \"b\"]", variables.get("names"));
        assertEquals("{\n  env = \"prod\"\n}", variables.get("tags"));
        assertEquals("", variables.get("empty"));
        assertEquals(List.of("example", "count", "enabled", "names", "tags", "empty"), new ArrayList<>(variables.keySet()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void decodesTheEscapesHclwriteProducesForStrings() {
        assertEquals("line one\nline two\r\tend", parse(pair("a", "\"line one\\nline two\\r\\tend\"")).get("a"));
        assertEquals("say \"hi\" \\ path", parse(pair("a", "\"say \\\"hi\\\" \\\\ path\"")).get("a"));
        assertEquals("${not} %{interpolated}", parse(pair("a", "\"$${not} %%{interpolated}\"")).get("a"));
        assertEquals("caf\u00e9 \uD83D\uDE00", parse(pair("a", "\"caf\\u00E9 \\U0001F600\"")).get("a"));
        assertEquals("100% $5 {x}", parse(pair("a", "\"100% $5 {x}\"")).get("a"));
        assertEquals("  padded  ", parse(pair("a", "  \"  padded  \"\n")).get("a"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullLiteralLeavesTheVariableToTheWorkspaceOrItsDefault() {
        assertEquals(Map.of("kept", "x"), parse(pair("unset", "null"), pair("kept", "\"x\"")));
    }

    @Test
    void absentOrEmptyListsAreNoVariables() {
        assertTrue(CliRunVariables.parse(null).isEmpty());
        assertTrue(CliRunVariables.parse(List.of()).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsMalformedEntriesWithoutEchoingValues() {
        String secret = "\"private-marker";
        for (Object bad : new Object[]{
                "not a list", List.of("text"), List.of(Map.of("key", "a")), List.of(Map.of("value", "1")),
                List.of(Map.of("key", 1, "value", "\"x\"")), List.of(Map.of("key", "a", "value", 1)),
                List.of(pair("bad name", "1")), List.of(pair("", "1")), List.of(pair("a", "")), List.of(pair("a", "  ")),
                List.of(pair("a", secret)), List.of(pair("a", secret + "\" trailing\"")),
                List.of(pair("a", "\"unfinished\\\"")), List.of(pair("a", "\"bad \\q escape\"")),
                List.of(pair("a", "\"short \\u12\"")), List.of(pair("a", "\"nan \\uZZZZ\"")),
                List.of(pair("a", "\"${interpolated}\"")), List.of(pair("a", "\"%{if x}y%{endif}\"")),
                List.of(pair("a", "\"1\"\"2\"")), List.of(pair("a", "\"")),
                List.of(pair("a", "\"x\""), pair("a", "\"y\""))}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> CliRunVariables.parse(bad), String.valueOf(bad));
            assertFalse(error.getMessage().contains("private-marker"), error.getMessage());
        }
    }
}
