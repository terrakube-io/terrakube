package io.terrakube.registry.service.mirror;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Temporary files and directories only the current user can read or write, so nothing another local user
 * can reach is created in a shared directory such as {@code /tmp}. On a file system without POSIX
 * permissions the platform default is used.
 */
public final class PrivateTemp {

    private static final FileAttribute<Set<PosixFilePermission>> FILE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final FileAttribute<Set<PosixFilePermission>> DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    private PrivateTemp() {
    }

    /** A new file in {@code directory}, or in the default temporary directory when it is {@code null}. */
    public static Path file(Path directory, String prefix, String suffix) throws IOException {
        try {
            return directory != null
                    ? Files.createTempFile(directory, prefix, suffix, FILE)
                    : Files.createTempFile(prefix, suffix, FILE);
        } catch (UnsupportedOperationException notPosix) {
            return directory != null
                    ? Files.createTempFile(directory, prefix, suffix)
                    : Files.createTempFile(prefix, suffix);
        }
    }

    /** A new directory in the default temporary directory. */
    public static Path directory(String prefix) throws IOException {
        try {
            return Files.createTempDirectory(prefix, DIRECTORY);
        } catch (UnsupportedOperationException notPosix) {
            return Files.createTempDirectory(prefix);
        }
    }
}
