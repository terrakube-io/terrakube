package io.terrakube.registry.service.mirror;

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
        Path file = PrivateTemp.file(directory, "mirror", ".zip");

        assertThat(file.getParent()).isEqualTo(directory);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        Files.delete(file);
    }

    @Test
    void aFileWithoutADirectoryIsReadableOnlyByItsOwner() throws Exception {
        Path file = PrivateTemp.file(null, "mirror", ".zip");
        try {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
