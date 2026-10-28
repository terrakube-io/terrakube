package io.terrakube.executor.service.terraform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RunVariablesFileTest {
    @TempDir File directory;

    @Test
    void writesOneLinePerVariableSortedAndReadableByTheOwnerOnly() throws IOException {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("names", "[\"a\", \"b\"]");
        variables.put("count", "5");
        variables.put("tags", "{\n  env = \"prod\"\n}");
        File file = RunVariablesFile.write(directory, variables);
        assertEquals(new File(directory, RunVariablesFile.FILE_NAME), file);
        assertEquals("count = 5\nnames = [\"a\", \"b\"]\ntags = {\n  env = \"prod\"\n}\n", Files.readString(file.toPath()));
        if (file.toPath().getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file.toPath()));
        }
    }

    @Test
    void writingAgainReplacesTheFileAndDeleteRemovesIt() throws IOException {
        RunVariablesFile.write(directory, Map.of("a", "1"));
        File file = RunVariablesFile.write(directory, Map.of("b", "2"));
        assertEquals("b = 2\n", Files.readString(file.toPath()));
        RunVariablesFile.delete(directory);
        assertFalse(file.exists());
        assertDoesNotThrow(() -> RunVariablesFile.delete(directory));
    }

    @Test
    void refusesNamesAndValuesThatCouldBreakOutOfTheFile() {
        for (Map<String, String> bad : java.util.List.of(Map.of("bad name", "1"), Map.of("a=b", "1"), Map.of("a", ""),
                Map.of("1a", "1"), Map.of("", "1"))) {
            IOException error = assertThrows(IOException.class, () -> RunVariablesFile.write(directory, bad));
            assertFalse(error.getMessage().contains("1"));
        }
    }

    @Test
    void addsTheVarFileAsOneQuotedWordAfterExistingArguments() {
        File file = new File("/tmp/exec dir/terrakube-run-variables.tfvars");
        assertEquals("'-var-file=/tmp/exec dir/terrakube-run-variables.tfvars'", RunVariablesFile.addVarFile(null, file));
        assertEquals("'-var-file=/tmp/exec dir/terrakube-run-variables.tfvars'", RunVariablesFile.addVarFile("  ", file));
        assertEquals("-target=a.b '-var-file=/tmp/exec dir/terrakube-run-variables.tfvars'", RunVariablesFile.addVarFile("-target=a.b", file));
        assertEquals("'-var-file=/tmp/it'\\''s/x.tfvars'", RunVariablesFile.addVarFile(null, new File("/tmp/it's/x.tfvars")));
    }
}
