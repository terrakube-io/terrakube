package io.terrakube.api.plugin.storage;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies a one-shot upload stream (a configuration-version tarball) to a temp file, so a backend
 * can upload it without holding it in memory and retry from the file (#3852). The caller deletes
 * the returned file with {@link #delete(Path)}.
 */
@Slf4j
public final class UploadSpool {

    private UploadSpool() {
    }

    /**
     * @param directory where the temp file goes; {@code null} means the system temp directory
     */
    public static Path spool(String contentId, InputStream inputStream, Path directory) {
        Path file;
        try {
            file = directory == null
                    ? Files.createTempFile("terrakube-content-", ".tar.gz")
                    : Files.createTempFile(directory, "terrakube-content-", ".tar.gz");
        } catch (IOException e) {
            throw new StorageUnavailableException("Unable to buffer uploaded content for " + contentId, e);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = readUpload(contentId, inputStream, buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return file;
        } catch (IOException e) {
            delete(file);
            throw new StorageUnavailableException("Unable to buffer uploaded content for " + contentId, e);
        } catch (RuntimeException e) {
            delete(file);
            throw e;
        }
    }

    // Reading the upload's own request body failed - can't be retried (it's a one-shot stream),
    // so fail the upload instead of silently marking it uploaded (#3671).
    private static int readUpload(String contentId, InputStream inputStream, byte[] buffer) {
        try {
            return inputStream.read(buffer);
        } catch (IOException e) {
            throw new StorageUnavailableException("Unable to read uploaded content for " + contentId, e);
        }
    }

    public static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Unable to delete temp upload file {}: {}", file, e.getMessage());
        }
    }
}
