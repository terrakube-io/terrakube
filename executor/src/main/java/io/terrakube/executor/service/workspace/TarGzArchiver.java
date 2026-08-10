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
import java.util.List;

@Slf4j
@Component
public class TarGzArchiver {

    public void extract(InputStream in, String destinationFilePath) throws IOException {
        GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(in);
        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry entry;

            while ((entry = (TarArchiveEntry) tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    File f = new File(String.format("%s/%s", destinationFilePath, entry.getName()));
                    log.debug("Creating folder: {}", f.getCanonicalPath());
                    String canonicalDestinationPath = f.getCanonicalPath();

                    if (!canonicalDestinationPath.startsWith(destinationFilePath)) {
                        throw new IOException("Entry is outside of the target directory");
                    }

                    boolean created = f.mkdir();
                    if (!created) {
                        log.info("Unable to create directory '{}', during extraction of archive contents.\n",
                                f.getAbsolutePath());
                    }
                } else {
                    int count;
                    byte data[] = new byte[2048];
                    File f = new File(String.format("%s/%s", destinationFilePath, entry.getName()));
                    String canonicalDestinationPath = f.getCanonicalPath();

                    if (!canonicalDestinationPath.startsWith(destinationFilePath)) {
                        throw new IOException("Entry is outside of the target directory");
                    }
                    if (!f.exists()) {
                        f.getParentFile().mkdirs();
                        if (f.createNewFile()) {
                            log.debug("File created: {}", f.getCanonicalPath());
                        }
                    }
                    FileOutputStream fos = new FileOutputStream(f.getCanonicalPath(), false);
                    log.info("Adding file {} to workspace context", destinationFilePath + "/" + entry.getName());
                    try (BufferedOutputStream dest = new BufferedOutputStream(fos, 2048)) {
                        while ((count = tarIn.read(data, 0, 2048)) != -1) {
                            dest.write(data, 0, count);
                        }
                    }
                }
            }

            log.info("Untar completed successfully!");
        }
    }

    public void create(File outputTarGz, File baseDirectory, List<File> filesToInclude) throws IOException {
        String basePath = baseDirectory.getCanonicalPath();
        try (OutputStream fos = new FileOutputStream(outputTarGz);
             GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(fos);
             TarArchiveOutputStream tarOs = new TarArchiveOutputStream(gzos)) {
            tarOs.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            for (File file : filesToInclude) {
                String canonicalPath = file.getCanonicalPath();
                if (!canonicalPath.startsWith(basePath)) {
                    throw new IOException("File to archive is outside of the base directory: " + canonicalPath);
                }
                String relativeName = canonicalPath.substring(basePath.length())
                        .replace(File.separatorChar, '/')
                        .replaceFirst("^/", "");

                TarArchiveEntry entry = new TarArchiveEntry(file, relativeName);
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
}
