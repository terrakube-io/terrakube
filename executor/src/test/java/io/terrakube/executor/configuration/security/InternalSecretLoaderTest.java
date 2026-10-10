package io.terrakube.executor.configuration.security;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class InternalSecretLoaderTest {

    @TempDir
    File tempDir;

    @Test
    void testDirectSecretTakesPrecedence() throws IOException {
        File secretFile = new File(tempDir, "secret.txt");
        FileUtils.writeStringToFile(secretFile, "file-secret-value", StandardCharsets.UTF_8);

        InternalSecretLoader loader = new InternalSecretLoader("direct-secret-value", secretFile.getAbsolutePath());

        assertEquals("direct-secret-value", loader.getInternalSecret());
        assertTrue(secretFile.exists(), "Direct secret should not delete the file");
    }

    @Test
    void testLoadSecretFromFileAndShredAndDelete() throws IOException {
        File secretFile = new File(tempDir, "secret.txt");
        String secretContent = "super-secret-token-12345";
        FileUtils.writeStringToFile(secretFile, secretContent, StandardCharsets.UTF_8);

        assertTrue(secretFile.exists());

        InternalSecretLoader loader = new InternalSecretLoader(null, secretFile.getAbsolutePath());

        assertEquals(secretContent, loader.getInternalSecret());
        assertFalse(secretFile.exists(), "Secret file must be shredded and deleted after being read into memory");
    }

    @Test
    void testLoadSecretFromBlankDirectSecretFallsBackToFile() throws IOException {
        File secretFile = new File(tempDir, "secret.txt");
        String secretContent = "fallback-secret-value";
        FileUtils.writeStringToFile(secretFile, secretContent, StandardCharsets.UTF_8);

        InternalSecretLoader loader = new InternalSecretLoader("   ", secretFile.getAbsolutePath());

        assertEquals(secretContent, loader.getInternalSecret());
        assertFalse(secretFile.exists(), "File must be deleted when falling back from blank direct secret");
    }

    @Test
    void testNonExistentFileHandlesGracefully() {
        File nonExistent = new File(tempDir, "does-not-exist.txt");

        InternalSecretLoader loader = new InternalSecretLoader("", nonExistent.getAbsolutePath());

        assertEquals("", loader.getInternalSecret());
    }

    @Test
    void testNullAndEmptyInputsReturnEmptyString() {
        InternalSecretLoader loader = new InternalSecretLoader(null, null);
        assertEquals("", loader.getInternalSecret());

        InternalSecretLoader loader2 = new InternalSecretLoader("  ", "");
        assertEquals("", loader2.getInternalSecret());
    }
}
