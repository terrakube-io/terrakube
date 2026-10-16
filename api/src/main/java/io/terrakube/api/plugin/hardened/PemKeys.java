package io.terrakube.api.plugin.hardened;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class PemKeys {

    private PemKeys() {
    }

    static RSAPrivateKey readPrivateKey(String path) throws IOException, GeneralSecurityException {
        byte[] der = decode(path, "PRIVATE KEY");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    static RSAPublicKey readPublicKey(String path) throws IOException, GeneralSecurityException {
        byte[] der = decode(path, "PUBLIC KEY");
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] decode(String path, String type) throws IOException {
        String pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int start = pem.indexOf(begin);
        int stop = pem.indexOf(end);
        if (start < 0 || stop < start) {
            throw new IOException(path + " is not a PEM encoded " + type);
        }
        String body = pem.substring(start + begin.length(), stop).replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
