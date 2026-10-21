package io.terrakube.api.plugin.scheduler.spool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class SpoolCompressionUtil {

    public static final int MAX_COMPRESSED_BYTES = 5 * 1024 * 1024;     // 5 MB
    public static final int MAX_UNCOMPRESSED_BYTES = 15 * 1024 * 1024;  // 15 MB

    private SpoolCompressionUtil() {
    }

    public static byte[] gzip(byte[] input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input);
        }
        return baos.toByteArray();
    }

    public static byte[] gunzip(byte[] compressed) throws IOException {
        return gunzip(compressed, MAX_UNCOMPRESSED_BYTES);
    }

    public static byte[] gunzip(byte[] compressed, int maxUncompressedBytes) throws IOException {
        if (compressed == null) {
            return new byte[0];
        }
        if (compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed spool payload exceeds maximum size of " + MAX_COMPRESSED_BYTES + " bytes");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = gzis.read(buffer)) != -1) {
                total += read;
                if (total > maxUncompressedBytes) {
                    throw new IOException("Uncompressed spool payload exceeds maximum size of " + maxUncompressedBytes + " bytes");
                }
                baos.write(buffer, 0, read);
            }
        }
        return baos.toByteArray();
    }
}
