package dev.holmzi.eldervale.updater.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.LongConsumer;

public final class Downloads {
    private Downloads() {}

    public static void validateUrl(URI uri) throws IOException {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getPort() != -1 && uri.getPort() != 443
                || !(host.equals("api.github.com") || host.equals("github.com")
                || host.equals("cdn.modrinth.com") || host.equals("gitlab.com")
                || host.equals("raw.githubusercontent.com") || host.endsWith(".githubusercontent.com"))) {
            throw new IOException("Download URL is not allowed: " + host);
        }
    }

    private static HttpURLConnection connect(URI initial) throws IOException {
        URI uri = initial;
        for (int redirects = 0; redirects <= 6; redirects++) {
            validateUrl(uri);
            var connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
            connection.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Eldervale-Updater/1.0 (holmzi/eldervale)");
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
            int status;
            try {
                status = connection.getResponseCode();
            } catch (IOException exception) {
                connection.disconnect();
                throw exception;
            }
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    throw new IOException("Redirect without a location");
                }
                uri = uri.resolve(location);
                continue;
            }
            if (status != 200) {
                connection.disconnect();
                throw new IOException("Download server returned HTTP " + status
                        + (status == 403 || status == 429 ? " (possible rate limit)" : ""));
            }
            return connection;
        }
        throw new IOException("Too many redirects");
    }

    public static byte[] json(URI uri) throws IOException {
        var connection = connect(uri);
        try (InputStream input = connection.getInputStream()) {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) {
                throw new IOException("Server response is too large");
            }
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    public static void file(URI uri, Path target, long expectedSize, LongConsumer progress) throws IOException {
        if (expectedSize < 0 || expectedSize > 4L * 1024 * 1024 * 1024) {
            throw new IOException("Invalid download size");
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".download-", ".part");
        HttpURLConnection connection = null;
        try {
            connection = connect(uri);
            long declared = connection.getContentLengthLong();
            if (declared >= 0 && declared != expectedSize) {
                throw new IOException("Download size differs from the declared size");
            }
            try (var input = connection.getInputStream(); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[131072];
                long received = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }
                    received += count;
                    if (received > expectedSize) {
                        throw new IOException("Declared file size exceeded");
                    }
                    output.write(buffer, 0, count);
                    progress.accept(received);
                }
                if (received != expectedSize) {
                    throw new IOException("Incomplete download");
                }
            }
            SafeFiles.move(temporary, target);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            Files.deleteIfExists(temporary);
        }
    }
}
