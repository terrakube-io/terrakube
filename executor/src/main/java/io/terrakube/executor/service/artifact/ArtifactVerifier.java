package io.terrakube.executor.service.artifact;

import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.service.workspace.TarGzArchiver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

@Slf4j
@Component
public class ArtifactVerifier {

    private final TarGzArchiver tarGzArchiver;

    public ArtifactVerifier(TarGzArchiver tarGzArchiver) {
        this.tarGzArchiver = tarGzArchiver;
    }

    public void verifyAndExtract(byte[] artifactBytes, String expectedChecksumHex, File workingDirectory)
            throws ArtifactVerificationException {
        String actualChecksum = DigestUtils.sha256Hex(artifactBytes);
        if (expectedChecksumHex != null && !expectedChecksumHex.isBlank()
                && !actualChecksum.equalsIgnoreCase(expectedChecksumHex)) {
            throw new ArtifactVerificationException(
                    "Plan artifacts checksum mismatch: expected " + expectedChecksumHex
                            + " but downloaded bundle hashed to " + actualChecksum);
        }

        try {
            tarGzArchiver.extract(new ByteArrayInputStream(artifactBytes), workingDirectory.getCanonicalPath());
        } catch (IOException e) {
            log.error("Failed to extract plan artifacts bundle: {}", e.getMessage());
            throw new ArtifactVerificationException("Failed to extract plan artifacts bundle: " + e.getMessage());
        }
    }
}
