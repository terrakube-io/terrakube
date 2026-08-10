package io.terrakube.executor.service.workspace;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TarGzArchiverTest {

    private final TarGzArchiver archiver = new TarGzArchiver();

    @Test
    void createThenExtractRoundTrips(@TempDir Path sourceDir, @TempDir Path extractDir) throws Exception {
        File baseDirectory = sourceDir.toFile();
        File nested = new File(baseDirectory, "build/sub");
        nested.mkdirs();
        File file1 = new File(baseDirectory, "build/output.zip");
        File file2 = new File(baseDirectory, "build/sub/nested.txt");
        FileUtils.writeStringToFile(file1, "zip-bytes", Charset.defaultCharset());
        FileUtils.writeStringToFile(file2, "nested-content", Charset.defaultCharset());

        File tarGz = new File(baseDirectory, "artifacts.tar.gz");
        archiver.create(tarGz, baseDirectory, List.of(file1, file2));

        assertTrue(tarGz.exists());

        try (FileInputStream fis = new FileInputStream(tarGz)) {
            archiver.extract(fis, extractDir.toFile().getCanonicalPath());
        }

        File extractedFile1 = new File(extractDir.toFile(), "build/output.zip");
        File extractedFile2 = new File(extractDir.toFile(), "build/sub/nested.txt");
        assertTrue(extractedFile1.exists());
        assertTrue(extractedFile2.exists());
        assertEquals("zip-bytes", FileUtils.readFileToString(extractedFile1, Charset.defaultCharset()));
        assertEquals("nested-content", FileUtils.readFileToString(extractedFile2, Charset.defaultCharset()));
    }
}
