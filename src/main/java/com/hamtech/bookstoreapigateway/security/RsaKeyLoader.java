package com.notfound.bookstoreapigateway.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Đọc RSA public key từ thư mục PEM (mặc định {@code /key/public.pem}).
 */
public final class RsaKeyLoader {

    private RsaKeyLoader() {
    }

    public static PublicKey loadPublicKey(Path keysDir, String fileName) {
        Path file = keysDir.resolve(fileName);
        try {
            String pem = Files.readString(file);
            if (pem.contains("BEGIN CERTIFICATE")) {
                return loadPublicKeyFromCertificate(pem);
            }
            byte[] der = decodePem(pem);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Không thể load public key từ " + file, e);
        }
    }

    public static Path resolveKeysDir(String keysDir) {
        Path path = Path.of(keysDir);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path).normalize();
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Thư mục JWT keys không tồn tại: " + path);
        }
        return path;
    }

    private static PublicKey loadPublicKeyFromCertificate(String pem) throws Exception {
        byte[] der = decodePem(pem);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (var is = new ByteArrayInputStream(der)) {
            X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
            return cert.getPublicKey();
        }
    }

    private static byte[] decodePem(String pem) {
        String normalized = pem.trim();
        for (String label : new String[]{"PUBLIC KEY", "CERTIFICATE"}) {
            if (normalized.contains("BEGIN " + label)) {
                String base64 = normalized
                        .replace("-----BEGIN " + label + "-----", "")
                        .replace("-----END " + label + "-----", "")
                        .replaceAll("\\s+", "");
                return Base64.getDecoder().decode(base64);
            }
        }
        return Base64.getDecoder().decode(normalized.replaceAll("\\s+", ""));
    }
}
