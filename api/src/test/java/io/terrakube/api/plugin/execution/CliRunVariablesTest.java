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

    @SafeVarargs
    private static Map<String, String> parse(Map<String, Object>... pairs) {
        return CliRunVariables.parse(List.of(pairs));
    }

    private static String argument(String literal) {
        return CliRunVariables.argumentValue("v", parse(pair("v", literal)).get("v"));
    }

    @Test
    void keepsEachLiteralAsReceivedInRequestOrder() {
        Map<String, String> variables = parse(
                pair("example", "\"from-file\""), pair("count", "5"), pair("enabled", "true"),
                pair("names", "[\"a\", \"b\"]"), pair("tags", "{\n  env = \"prod\"\n}"), pair("empty", "\"\""),
                pair("padded", "  \"x\"\n"));
        assertEquals(List.of("example", "count", "enabled", "names", "tags", "empty", "padded"), new ArrayList<>(variables.keySet()));
        assertEquals("\"from-file\"", variables.get("example"));
        assertEquals("{\n  env = \"prod\"\n}", variables.get("tags"));
        assertEquals("\"x\"", variables.get("padded"));
    }

    @Test
    void stringLiteralsBecomePlainTextForVarAndEveryOtherLiteralStaysHcl() {
        assertEquals("from-file", argument("\"from-file\""));
        assertEquals("", argument("\"\""));
        assertEquals("5", argument("5"));
        assertEquals("true", argument("true"));
        assertEquals("[\"a\", \"b\"]", argument("[\"a\", \"b\"]"));
        assertTrue(CliRunVariables.isString("\"x\""));
        assertTrue(CliRunVariables.isString("<<EOT\nx\nEOT"));
        for (String literal : new String[]{"5", "true", "[\"a\"]", "{a = 1}", "-1.5e3"}) {
            assertFalse(CliRunVariables.isString(literal), literal);
        }
    }

    @Test
    void decodesTheEscapesHclwriteProducesForStrings() {
        assertEquals("line one\nline two\r\tend", argument("\"line one\\nline two\\r\\tend\""));
        assertEquals("say \"hi\" \\ path", argument("\"say \\\"hi\\\" \\\\ path\""));
        assertEquals("${not} %{interpolated}", argument("\"$${not} %%{interpolated}\""));
        assertEquals("caf\u00e9 \uD83D\uDE00", argument("\"caf\\u00E9 \\U0001F600\""));
        assertEquals("100% $5 {x}", argument("\"100% $5 {x}\""));
    }

    @Test
    void decodesHeredocs() {
        assertEquals("line one\nline two\n", argument("<<EOT\nline one\nline two\nEOT"));
        assertEquals("  keep\n  indent\n", argument("<<EOT\n  keep\n  indent\nEOT"));
        assertEquals("one\n  two\n\nthree\n", argument("<<-EOT\n    one\n      two\n\n    three\n    EOT"));
        assertEquals("", argument("<<EOT\nEOT"));
        assertEquals("a\\nb \"q\" ${x}\n", argument("<<EOT\na\\nb \"q\" $${x}\nEOT"));
        assertEquals("crlf\n", argument("<<EOT\r\ncrlf\r\nEOT"));
        assertEquals("inner EOT word\n", argument("<<EOT\ninner EOT word\nEOT"));
    }

    @Test
    void acceptsTheHclLiteralForms() {
        for (String literal : new String[]{
                "0", "-1", "3.14", "1e3", "-2.5E-10", "100000000000000000000", "true", "false",
                "[]", "[ ]", "[1, 2, 3]", "[\"a\", \"b\",]", "[\n  \"a\",\n  \"b\"\n]", "[\n  \"a\"\n  \"b\"\n]",
                "[[1, 2], [3]]", "{}", "{ a = 1 }", "{a = 1, b = \"two\"}", "{\n  a = 1\n  b = [true, null]\n}",
                "{ \"quoted key\" = 1, other-key = { nested = \"v\" } }", "{ a : 1 }", "{\n  a = 1,\n}"}) {
            assertDoesNotThrow(() -> parse(pair("v", literal)), literal);
        }
    }

    @Test
    void nullLiteralLeavesTheVariableToTheWorkspaceOrItsDefault() {
        assertEquals(List.of("kept"), new ArrayList<>(parse(pair("unset", "null"), pair("kept", "\"x\"")).keySet()));
    }

    @Test
    void absentOrEmptyListsAreNoVariables() {
        assertTrue(CliRunVariables.parse(null).isEmpty());
        assertTrue(CliRunVariables.parse(List.of()).isEmpty());
    }

    @Test
    void rejectsMalformedEntriesAndLiteralsWithoutEchoingValues() {
        String secret = "private-marker";
        for (Object bad : new Object[]{
                "not a list", List.of("text"), List.of(Map.of("key", "a")), List.of(Map.of("value", "1")),
                List.of(Map.of("key", 1, "value", "\"x\"")), List.of(Map.of("key", "a", "value", 1)),
                List.of(pair("bad name", "1")), List.of(pair("", "1")), List.of(pair("a", "")), List.of(pair("a", "  ")),
                List.of(pair("a", "\"" + secret)), List.of(pair("a", "\"" + secret + "\" trailing\"")),
                List.of(pair("a", "\"unfinished\\\"")), List.of(pair("a", "\"bad \\q escape\"")),
                List.of(pair("a", "\"short \\u12\"")), List.of(pair("a", "\"nan \\uZZZZ\"")),
                List.of(pair("a", "\"${interpolated}\"")), List.of(pair("a", "\"%{if x}y%{endif}\"")),
                List.of(pair("a", "\"1\"\"2\"")), List.of(pair("a", "\"")), List.of(pair("a", "\"nul \\u0000\"")),
                List.of(pair("a", "\"line\nbreak\"")),
                List.of(pair("a", "[\"" + secret + "\", ")), List.of(pair("a", "[1 2]")), List.of(pair("a", "[1,, 2]")),
                List.of(pair("a", "{ a = }")), List.of(pair("a", "{ a 1 }")), List.of(pair("a", "{ a = 1 b = 2 }")),
                List.of(pair("a", "{ a = 1")), List.of(pair("a", "[" + secret + "]")), List.of(pair("a", "var." + secret)),
                List.of(pair("a", "upper(\"" + secret + "\")")), List.of(pair("a", "\"" + secret + "\" + 1")),
                List.of(pair("a", "1 + 2")), List.of(pair("a", "5x")), List.of(pair("a", "+5")), List.of(pair("a", "0x10")),
                List.of(pair("a", "truee")), List.of(pair("a", "[for x in y : x]")), List.of(pair("a", "# " + secret)),
                List.of(pair("a", "<<EOT\n" + secret)), List.of(pair("a", "<<EOT\nline\nEOTX")),
                List.of(pair("a", "<<EOT\n${x}\nEOT")), List.of(pair("a", "<<\nx\n")), List.of(pair("a", "<<EOT\nx\nEOT\nextra")),
                List.of(pair("a", "[<<EOT\nx\nEOT\n]")),
                List.of(pair("a", "\"x\""), pair("a", "\"y\""))}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> CliRunVariables.parse(bad), String.valueOf(bad));
            assertFalse(error.getMessage().contains(secret), error.getMessage());
        }
    }
}
