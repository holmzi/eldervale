package dev.holmzi.eldervale.updater.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {
    private Hashes() {}

    public static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String hash(Path path, String algorithm) throws IOException {
        var digest = digest(algorithm);
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[131072];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Operation cancelled");
                }
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static boolean matches(Path path, Manifest.FileInfo info) throws IOException {
        return Files.isRegularFile(path) && Files.size(path) == info.size()
                && hash(path, "SHA-512").equalsIgnoreCase(info.sha512());
    }
}
