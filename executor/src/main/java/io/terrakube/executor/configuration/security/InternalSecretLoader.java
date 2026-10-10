package io.terrakube.executor.configuration.security;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class InternalSecretLoader {

    @Getter
    private final String internalSecret;

    public InternalSecretLoader(
            @Value("${io.terrakube.client.secretKey:}") String internalJwtSecret,
            @Value("${io.terrakube.client.secretKeyFile:}") String internalSecretFile) {
        this.internalSecret = resolveAndPurgeSecret(internalJwtSecret, internalSecretFile);
    }

    private String resolveAndPurgeSecret(String directSecret, String secretFilePath) {
        if (directSecret != null && !directSecret.isBlank()) {
            return directSecret;
        }

        if (secretFilePath == null || secretFilePath.isBlank()) {
            log.warn("Neither internal secret nor secret file path was provided.");
            return "";
        }

        File file = new File(secretFilePath);
        if (!file.exists()) {
            log.warn("Internal secret file specified but does not exist: {}", secretFilePath);
            return "";
        }

        try {
            String secret = FileUtils.readFileToString(file, StandardCharsets.UTF_8).trim();
            shredAndDeleteFile(file);
            return secret;
        } catch (Exception e) {
            log.error("Failed to read internal secret file {}: {}", secretFilePath, e.getMessage());
            return "";
        }
    }

    private void shredAndDeleteFile(File file) {
        try {
            long length = file.length();
            if (length > 0 && file.canWrite()) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    byte[] zeros = new byte[(int) Math.min(length, 4096)];
                    fos.write(zeros);
                    fos.flush();
                }
            }
            if (!file.delete()) {
                log.warn("Unable to delete internal secret file {}. If mounted read-only without a writable emptyDir volume, the file remains readable on disk.", file.getAbsolutePath());
            } else {
                log.info("Successfully shredded and deleted internal secret file: {}", file.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("Failed to overwrite and delete internal secret file {}: {}. Ensure the volume is writable (e.g. emptyDir).", file.getAbsolutePath(), e.getMessage());
        }
    }
}
