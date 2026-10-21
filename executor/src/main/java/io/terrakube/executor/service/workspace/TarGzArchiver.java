package io.terrakube.executor.service.workspace;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

@Slf4j
@Component
public class TarGzArchiver {

    // Owner, group or other execute bit in a tar entry's mode.
    private static final int EXECUTE_BITS = 0111;

    /**
     * Unpacks a workspace archive (uploaded by the CLI) into {@code destinationFilePath}, keeping the
     * executable bit recorded in the archive, as a VCS checkout does, so scripts it carries can still
     * be run (#1773). No size limit: the archive comes from the user's own upload, as before.
     */
    public void extract(InputStream in, String destinationFilePath) throws IOException {
        Path destinationDirectory = Path.of(destinationFilePath).toAbsolutePath().normalize();
        Files.createDirectories(destinationDirectory);
        Path destination = destinationDirectory.toRealPath();
        try (GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(in);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                Path target = resolveInside(destination, entry.getName());
                if (entry.isDirectory()) {
                    log.debug("Creating folder: {}", target);
                    Files.createDirectories(target);
                } else {
                    rejectDestinationItself(destination, target, entry);
                    Files.createDirectories(target.getParent());
                    // resolveInside only compares names: a link already in the destination could still
                    // point the parent, or the file itself, outside it.
                    if (!target.getParent().toRealPath().startsWith(destination)) {
                        throw new IOException("Entry is outside of the target directory: " + entry.getName());
                    }
                    log.info("Adding file {} to workspace context", target);
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), 8192)) {
                        IOUtils.copy(tarIn, out);
                    }
                    restoreExecuteBit(entry, target);
                }
            }
            log.info("Untar completed successfully!");
        }
    }

    /**
     * Unpacks a bundle that is not the user's own upload (plan artifacts read back from storage). On
     * top of {@link #extract}: only directories and regular files are accepted (a link, device or fifo
     * entry fails the extraction, so nothing can be redirected outside the destination), nothing is
     * written through a symbolic link, and the unpacked size and file count are capped, so a corrupt
     * or hostile bundle cannot fill the pod's disk.
     *
     * @return the number of files unpacked.
     * @throws IOException when the archive is invalid, holds an unsupported entry, would write outside
     *                     {@code destination}, or exceeds {@code maxBytes} / {@code maxFiles}.
     */
    public int extractBounded(InputStream in, Path destinationDirectory, long maxBytes, int maxFiles) throws IOException {
        Files.createDirectories(destinationDirectory);
        Path destination = destinationDirectory.toRealPath();
        long totalBytes = 0;
        int files = 0;
        try (GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(in);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (!isDirectoryOrRegularFile(entry)) {
                    throw new IOException("Unsupported entry type in archive (only files and directories are allowed): " + entry.getName());
                }
                Path target = resolveInside(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                rejectDestinationItself(destination, target, entry);
                if (++files > maxFiles) {
                    throw new IOException("Archive holds more than " + maxFiles + " files");
                }
                Files.createDirectories(target.getParent());
                if (!target.getParent().toRealPath().startsWith(destination)) {
                    throw new IOException("Entry is outside of the target directory: " + entry.getName());
                }
                try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = tarIn.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > maxBytes) {
                            throw new IOException("Archive unpacks to more than " + maxBytes + " bytes");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                restoreExecuteBit(entry, target);
            }
        }
        return files;
    }

    public void create(File outputTarGz, File baseDirectory, List<File> filesToInclude) throws IOException {
        Path base = baseDirectory.toPath().toRealPath();
        try (OutputStream fos = new FileOutputStream(outputTarGz);
             GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(fos);
             TarArchiveOutputStream tarOs = new TarArchiveOutputStream(gzos)) {
            tarOs.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            for (File file : filesToInclude) {
                Path path = file.toPath().toRealPath();
                if (!path.startsWith(base)) {
                    throw new IOException("File to archive is outside of the base directory: " + path);
                }
                String relativeName = base.relativize(path).toString().replace(File.separatorChar, '/');

                TarArchiveEntry entry = new TarArchiveEntry(path, relativeName);
                // The entry keeps only a default mode, so record the one bit that matters here: a
                // Lambda bootstrap or a hook script has to stay executable when it is unpacked.
                entry.setMode(Files.isExecutable(path) ? 0755 : 0644);
                tarOs.putArchiveEntry(entry);
                try (InputStream fis = new FileInputStream(file)) {
                    IOUtils.copy(fis, tarOs);
                }
                tarOs.closeArchiveEntry();
            }
            tarOs.finish();
        }
        log.info("Created archive {} with {} entries", outputTarGz.getAbsolutePath(), filesToInclude.size());
    }

    /**
     * The entry's location under {@code destination}, which must be absolute and normalised. The
     * destination itself is allowed (a "./" directory entry), as {@link #rejectDestinationItself}
     * is what stops a file from being written over it.
     */
    private static Path resolveInside(Path destination, String entryName) throws IOException {
        Path target = destination.resolve(entryName).normalize();
        // Path.startsWith compares whole path elements, so a sibling such as "<dest>-other" is not
        // mistaken for being inside "<dest>" the way a string prefix check would.
        if (!target.startsWith(destination)) {
            throw new IOException("Entry is outside of the target directory: " + entryName);
        }
        return target;
    }

    private static void rejectDestinationItself(Path destination, Path target, TarArchiveEntry entry) throws IOException {
        if (target.equals(destination)) {
            throw new IOException("Entry is outside of the target directory: " + entry.getName());
        }
    }

    // TarArchiveEntry.isFile() is also true for a link or a device entry whose name does not end in "/",
    // so it cannot be the only check.
    private static boolean isDirectoryOrRegularFile(TarArchiveEntry entry) {
        if (entry.isDirectory()) {
            return true;
        }
        return entry.isFile() && !entry.isSymbolicLink() && !entry.isLink()
                && !entry.isCharacterDevice() && !entry.isBlockDevice() && !entry.isFIFO();
    }

    private static void restoreExecuteBit(TarArchiveEntry entry, Path target) {
        if ((entry.getMode() & EXECUTE_BITS) != 0 && !target.toFile().setExecutable(true, true)) {
            log.warn("Unable to set the executable permission on {}", target);
        }
    }
}
