package io.terrakube.executor.service.artifact;

import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.service.workspace.TarGzArchiver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactVerifierTest {

    private final TarGzArchiver tarGzArchiver = new TarGzArchiver();
    private final ArtifactVerifier verifier = new ArtifactVerifier(tarGzArchiver);

    @Test
    void matchingChecksumExtractsBundle(@TempDir Path sourceDir, @TempDir Path targetDir) throws Exception {
        File sourceFile = new File(sourceDir.toFile(), "build/output.zip");
        FileUtils.writeStringToFile(sourceFile, "zip-bytes", Charset.defaultCharset());
        File tarGz = new File(sourceDir.toFile(), "plan-artifacts.tar.gz");
        tarGzArchiver.create(tarGz, sourceDir.toFile(), List.of(sourceFile));
        byte[] bundleBytes = FileUtils.readFileToByteArray(tarGz);
        String checksum = sha256Hex(bundleBytes);

        verifier.verifyAndExtract(bundleBytes, checksum, targetDir.toFile());

        assertTrue(new File(targetDir.toFile(), "build/output.zip").exists());
    }

    @Test
    void mismatchedChecksumThrowsBeforeExtracting(@TempDir Path sourceDir, @TempDir Path targetDir) throws Exception {
        File sourceFile = new File(sourceDir.toFile(), "build/output.zip");
        FileUtils.writeStringToFile(sourceFile, "zip-bytes", Charset.defaultCharset());
        File tarGz = new File(sourceDir.toFile(), "plan-artifacts.tar.gz");
        tarGzArchiver.create(tarGz, sourceDir.toFile(), List.of(sourceFile));
        byte[] bundleBytes = FileUtils.readFileToByteArray(tarGz);

        ArtifactVerificationException thrown = assertThrows(ArtifactVerificationException.class,
                () -> verifier.verifyAndExtract(bundleBytes,
                        "0000000000000000000000000000000000000000000000000000000000000000", targetDir.toFile()));

        assertTrue(thrown.getMessage().contains("checksum mismatch"));
        assertFalse(new File(targetDir.toFile(), "build/output.zip").exists());
    }

    private String sha256Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
