package io.terrakube.executor.service.workspace;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TarGzArchiverTest {

    private final TarGzArchiver archiver = new TarGzArchiver();

    @Test
    void createThenExtractRoundTrips(@TempDir Path sourceDir, @TempDir Path extractDir) throws Exception {
        File baseDirectory = sourceDir.toFile();
        File file1 = new File(baseDirectory, "build/output.zip");
        File file2 = new File(baseDirectory, "build/sub/nested.txt");
        FileUtils.writeStringToFile(file1, "zip-bytes", Charset.defaultCharset());
        FileUtils.writeStringToFile(file2, "nested-content", Charset.defaultCharset());

        File tarGz = new File(extractDir.toFile().getParentFile(), "artifacts-roundtrip.tar.gz");
        try {
            archiver.create(tarGz, baseDirectory, List.of(file1, file2));
            try (FileInputStream fis = new FileInputStream(tarGz)) {
                archiver.extract(fis, extractDir.toFile().getCanonicalPath());
            }
        } finally {
            tarGz.delete();
        }

        assertEquals("zip-bytes", FileUtils.readFileToString(new File(extractDir.toFile(), "build/output.zip"), Charset.defaultCharset()));
        assertEquals("nested-content", FileUtils.readFileToString(new File(extractDir.toFile(), "build/sub/nested.txt"), Charset.defaultCharset()));
    }

    @Test
    void theExecutableBitSurvivesTheRoundTripInBothModes(@TempDir Path sourceDir, @TempDir Path lenient, @TempDir Path bounded) throws Exception {
        File script = new File(sourceDir.toFile(), "build/bootstrap");
        FileUtils.writeStringToFile(script, "#!/bin/sh\n", Charset.defaultCharset());
        assertTrue(script.setExecutable(true));
        File plain = new File(sourceDir.toFile(), "build/data.txt");
        FileUtils.writeStringToFile(plain, "data", Charset.defaultCharset());
        byte[] archive = archiveOf(sourceDir.toFile(), script, plain);

        archiver.extract(new ByteArrayInputStream(archive), lenient.toFile().getCanonicalPath());
        archiver.extractBounded(new ByteArrayInputStream(archive), bounded, 1_000_000, 100);

        for (Path root : List.of(lenient, bounded)) {
            assertTrue(new File(root.toFile(), "build/bootstrap").canExecute(), "bootstrap in " + root);
            assertFalse(new File(root.toFile(), "build/data.txt").canExecute(), "data.txt in " + root);
        }
    }

    @Test
    void aDotSlashDirectoryEntryIsAccepted(@TempDir Path extractDir) throws Exception {
        // `tar czf x.tgz .` starts with a "./" entry: the destination itself
        byte[] archive = tar(out -> {
            addDirectory(out, "./");
            addFile(out, "./main.tf", "resource");
        });

        archiver.extract(new ByteArrayInputStream(archive), extractDir.toFile().getCanonicalPath());
        assertEquals("resource", Files.readString(extractDir.resolve("main.tf")));

        Path other = Files.createTempDirectory(extractDir, "bounded");
        archiver.extractBounded(new ByteArrayInputStream(archive), other, 1_000, 10);
        assertEquals("resource", Files.readString(other.resolve("main.tf")));
    }

    @Test
    void anEntryEscapingTheDestinationIsRejectedInBothModes(@TempDir Path base) throws Exception {
        Path destination = Files.createDirectory(base.resolve("work"));
        byte[] archive = tar(out -> addFile(out, "../escaped.txt", "x"));

        assertThrows(IOException.class, () -> archiver.extract(new ByteArrayInputStream(archive), destination.toString()));
        assertThrows(IOException.class, () -> archiver.extractBounded(new ByteArrayInputStream(archive), destination, 1_000, 10));
        assertFalse(Files.exists(base.resolve("escaped.txt")));
    }

    @Test
    void anEntryInASiblingDirectoryWithTheSamePrefixIsRejected(@TempDir Path base) throws Exception {
        // a string prefix check would call ".../work-evil/x" "inside" ".../work"
        Path destination = Files.createDirectory(base.resolve("work"));
        Files.createDirectory(base.resolve("work-evil"));
        byte[] archive = tar(out -> addFile(out, "../work-evil/x.txt", "x"));

        assertThrows(IOException.class, () -> archiver.extract(new ByteArrayInputStream(archive), destination.toString()));
        assertThrows(IOException.class, () -> archiver.extractBounded(new ByteArrayInputStream(archive), destination, 1_000, 10));
        assertFalse(Files.exists(base.resolve("work-evil/x.txt")));
    }

    @Test
    void anAbsoluteEntryNameCannotLeaveTheDestination(@TempDir Path base) throws Exception {
        Path destination = Files.createDirectory(base.resolve("work"));
        File target = new File(base.toFile(), "absolute.txt");
        byte[] archive = tar(out -> addFile(out, target.getAbsolutePath(), "x"));

        // resolved under the destination, never at the absolute path
        archiver.extractBounded(new ByteArrayInputStream(archive), destination, 1_000, 10);

        assertFalse(target.exists());
    }

    @Test
    void boundedExtractionRefusesLinksAndSpecialEntries(@TempDir Path destination) throws Exception {
        byte[] symlink = tar(out -> {
            TarArchiveEntry entry = new TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK);
            entry.setLinkName("/etc/passwd");
            out.putArchiveEntry(entry);
            out.closeArchiveEntry();
        });
        byte[] hardlink = tar(out -> {
            TarArchiveEntry entry = new TarArchiveEntry("hard", TarArchiveEntry.LF_LINK);
            entry.setLinkName("other");
            out.putArchiveEntry(entry);
            out.closeArchiveEntry();
        });

        IOException thrown = assertThrows(IOException.class,
                () -> archiver.extractBounded(new ByteArrayInputStream(symlink), destination, 1_000, 10));
        assertTrue(thrown.getMessage().contains("Unsupported entry type"));
        assertThrows(IOException.class, () -> archiver.extractBounded(new ByteArrayInputStream(hardlink), destination, 1_000, 10));
        assertFalse(Files.exists(destination.resolve("link")));
    }

    @Test
    void boundedExtractionNeverWritesThroughAnExistingSymlink(@TempDir Path base) throws Exception {
        Path destination = Files.createDirectory(base.resolve("work"));
        Path outside = Files.createDirectory(base.resolve("outside"));
        Files.createSymbolicLink(destination.resolve("build"), outside);
        byte[] archive = tar(out -> addFile(out, "build/x.txt", "x"));

        assertThrows(IOException.class, () -> archiver.extractBounded(new ByteArrayInputStream(archive), destination, 1_000, 10));

        assertFalse(Files.exists(outside.resolve("x.txt")));
    }

    @Test
    void theCliUploadExtractionNeverWritesThroughAnExistingSymlinkDirectory(@TempDir Path base) throws Exception {
        Path destination = Files.createDirectory(base.resolve("work"));
        Path outside = Files.createDirectory(base.resolve("outside"));
        Files.createSymbolicLink(destination.resolve("shared"), outside);
        byte[] archive = tar(out -> addFile(out, "shared/authorized_keys", "ssh-rsa AAAA"));

        assertThrows(IOException.class, () -> archiver.extract(new ByteArrayInputStream(archive), destination.toString()));

        assertFalse(Files.exists(outside.resolve("authorized_keys")));
    }

    @Test
    void theCliUploadExtractionNeverWritesThroughAnExistingSymlinkedFile(@TempDir Path base) throws Exception {
        Path destination = Files.createDirectory(base.resolve("work"));
        Path secret = Files.writeString(base.resolve("secret.txt"), "keep");
        Files.createSymbolicLink(destination.resolve("run.sh"), secret);
        byte[] archive = tar(out -> addFile(out, "run.sh", "overwritten"));

        assertThrows(IOException.class, () -> archiver.extract(new ByteArrayInputStream(archive), destination.toString()));

        assertEquals("keep", Files.readString(secret));
    }

    @Test
    void theCliUploadExtractionStillCreatesTheDestinationAndNestedFiles(@TempDir Path base) throws Exception {
        Path destination = base.resolve("not-yet-created");
        byte[] archive = tar(out -> addFile(out, "a/b/main.tf", "resource"));

        archiver.extract(new ByteArrayInputStream(archive), destination.toString());

        assertEquals("resource", Files.readString(destination.resolve("a/b/main.tf")));
    }

    @Test
    void boundedExtractionRefusesToOverwriteASymlinkedFile(@TempDir Path base) throws Exception {
        Path destination = Files.createDirectory(base.resolve("work"));
        Path secret = Files.writeString(base.resolve("secret.txt"), "keep");
        Files.createSymbolicLink(destination.resolve("x.txt"), secret);
        byte[] archive = tar(out -> addFile(out, "x.txt", "overwritten"));

        assertThrows(IOException.class, () -> archiver.extractBounded(new ByteArrayInputStream(archive), destination, 1_000, 10));

        assertEquals("keep", Files.readString(secret));
    }

    @Test
    void boundedExtractionStopsAtTheSizeLimit(@TempDir Path destination) throws Exception {
        byte[] archive = tar(out -> {
            addFile(out, "a.bin", "x".repeat(600));
            addFile(out, "b.bin", "x".repeat(600));
        });

        IOException thrown = assertThrows(IOException.class,
                () -> archiver.extractBounded(new ByteArrayInputStream(archive), destination, 1_000, 10));

        assertTrue(thrown.getMessage().contains("1000"));
    }

    @Test
    void boundedExtractionAcceptsExactlyTheLimitAndRefusesOneMore(@TempDir Path destination, @TempDir Path other) throws Exception {
        byte[] archive = tar(out -> addFile(out, "a.bin", "x".repeat(1_000)));

        archiver.extractBounded(new ByteArrayInputStream(archive), destination, 1_000, 10);
        assertThrows(IOException.class, () -> archiver.extractBounded(new ByteArrayInputStream(archive), other, 999, 10));
    }

    @Test
    void boundedExtractionStopsAtTheFileCountLimit(@TempDir Path destination) throws Exception {
        byte[] archive = tar(out -> {
            addFile(out, "a.txt", "a");
            addFile(out, "b.txt", "b");
            addFile(out, "c.txt", "c");
        });

        IOException thrown = assertThrows(IOException.class,
                () -> archiver.extractBounded(new ByteArrayInputStream(archive), destination, 1_000, 2));

        assertTrue(thrown.getMessage().contains("more than 2 files"));
    }

    @Test
    void aTruncatedArchiveFailsInsteadOfPartiallySucceeding(@TempDir Path destination) throws Exception {
        byte[] archive = tar(out -> addFile(out, "a.bin", "x".repeat(5_000)));
        byte[] truncated = java.util.Arrays.copyOf(archive, archive.length / 2);

        assertThrows(IOException.class, () -> archiver.extractBounded(new ByteArrayInputStream(truncated), destination, 1_000_000, 10));
    }

    @Test
    void createRefusesAFileOutsideTheBaseDirectory(@TempDir Path base, @TempDir Path outside) throws Exception {
        File stray = new File(outside.toFile(), "stray.txt");
        FileUtils.writeStringToFile(stray, "x", Charset.defaultCharset());

        assertThrows(IOException.class, () -> archiver.create(new File(base.toFile(), "x.tar.gz"), base.toFile(), List.of(stray)));
    }

    // ----- helpers

    private byte[] archiveOf(File base, File... files) throws IOException {
        File out = Files.createTempFile("archive-test", ".tar.gz").toFile();
        try {
            archiver.create(out, base, List.of(files));
            return Files.readAllBytes(out.toPath());
        } finally {
            out.delete();
        }
    }

    private interface TarWriter {
        void write(TarArchiveOutputStream out) throws IOException;
    }

    private byte[] tar(TarWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
             TarArchiveOutputStream out = new TarArchiveOutputStream(gzip)) {
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            writer.write(out);
            out.finish();
        }
        return bytes.toByteArray();
    }

    private void addDirectory(TarArchiveOutputStream out, String name) throws IOException {
        out.putArchiveEntry(new TarArchiveEntry(name));
        out.closeArchiveEntry();
    }

    private void addFile(TarArchiveOutputStream out, String name, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(data.length);
        out.putArchiveEntry(entry);
        out.write(data);
        out.closeArchiveEntry();
    }
}
