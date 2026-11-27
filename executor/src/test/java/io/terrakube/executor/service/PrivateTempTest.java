package io.terrakube.executor.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateTempTest {

    @TempDir
    Path directory;

    @Test
    void aFileInADirectoryIsReadableOnlyByItsOwner() throws Exception {
        Path file = PrivateTemp.file(directory, "step-", ".log");

        assertThat(file.getParent()).isEqualTo(directory);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        Files.delete(file);
    }

    @Test
    void aFileWithoutADirectoryIsReadableOnlyByItsOwner() throws Exception {
        Path file = PrivateTemp.file(null, "step-", ".log");
        try {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aDirectoryIsAccessibleOnlyByItsOwner() throws Exception {
        Path created = PrivateTemp.directory("terrakube-test-");
        try {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(created))).isEqualTo("rwx------");
        } finally {
            Files.deleteIfExists(created);
        }
    }
}
