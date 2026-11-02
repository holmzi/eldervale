package dev.holmzi.eldervale.updater.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Mrpack implements AutoCloseable {
    public record Source(Manifest.FileInfo info, List<URI> downloads, String archiveEntry) {}
    private final ZipFile archive;
    private final Map<String, Source> sources = new TreeMap<>();
    private final Manifest manifest;

    public Mrpack(Path path) throws IOException {
        archive = new ZipFile(path.toFile(), StandardCharsets.UTF_8);
        try {
            Map<String, ZipEntry> entries = new LinkedHashMap<>();
            long total = 0;
            var enumeration = archive.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                SafeFiles.validateName(entry.isDirectory() ? name.substring(0, name.length() - 1) : name);
                if (entries.putIfAbsent(name, entry) != null || entries.size() > 100_000) {
                    throw new IOException("Duplicate entry or too many files in .mrpack");
                }
                if (entry.getSize() < 0 || entry.getSize() > 4L * 1024 * 1024 * 1024) {
                    throw new IOException("Invalid archive entry size");
                }
                total += entry.getSize();
                if (total > 32L * 1024 * 1024 * 1024) {
                    throw new IOException("Unpacked archive exceeds 32 GiB");
                }
            }
            ZipEntry index = entries.get("modrinth.index.json");
            if (index == null || index.getSize() > 16 * 1024 * 1024) {
                throw new IOException("Missing or invalid modrinth.index.json");
            }
            JsonObject data;
            try (var input = archive.getInputStream(index)) {
                data = JsonParser.parseString(new String(readLimited(input, 16 * 1024 * 1024),
                        StandardCharsets.UTF_8)).getAsJsonObject();
            }
            if (data.get("formatVersion").getAsInt() != 1
                    || !"minecraft".equals(data.get("game").getAsString())) {
                throw new IOException("Unsupported .mrpack format");
            }
            var dependencies = data.getAsJsonObject("dependencies");
            if (dependencies.has("forge") || dependencies.has("fabric-loader")
                    || dependencies.has("quilt-loader") || !dependencies.has("neoforge")) {
                throw new IOException("The modpack must use NeoForge");
            }
            var seen = new HashSet<String>();
            for (var element : data.getAsJsonArray("files")) {
                var file = element.getAsJsonObject();
                String name = file.get("path").getAsString();
                SafeFiles.validateName(name);
                if (!seen.add(name)) {
                    throw new IOException("Duplicate index path: " + name);
                }
                if (!SafeFiles.managed(name)) {
                    continue;
                }
                var environment = file.has("env") ? file.getAsJsonObject("env") : null;
                if (environment != null && environment.has("client")
                        && "unsupported".equals(environment.get("client").getAsString())) {
                    continue;
                }
                String hash = file.getAsJsonObject("hashes").get("sha512").getAsString();
                long size = file.get("fileSize").getAsLong();
                List<URI> downloads = new ArrayList<>();
                for (var url : file.getAsJsonArray("downloads")) {
                    URI address = URI.create(url.getAsString());
                    Downloads.validateUrl(address);
                    downloads.add(address);
                }
                if (downloads.isEmpty()) {
                    throw new IOException("Missing download URL: " + name);
                }
                sources.put(name, new Source(new Manifest.FileInfo(hash, size), List.copyOf(downloads), null));
            }
            for (String prefix : List.of("overrides/", "client-overrides/")) {
                for (var entry : entries.entrySet()) {
                    String name = entry.getKey();
                    if (!name.startsWith(prefix) || entry.getValue().isDirectory()) {
                        continue;
                    }
                    String target = name.substring(prefix.length());
                    if (SafeFiles.managed(target)) {
                        SafeFiles.requireManaged(target);
                        var digest = Hashes.digest("SHA-512");
                        long count;
                        try (var input = archive.getInputStream(entry.getValue())) {
                            count = transfer(input, null, digest, entry.getValue().getSize());
                        }
                        sources.put(target, new Source(new Manifest.FileInfo(
                                HexFormat.of().formatHex(digest.digest()), count), List.of(), name));
                    }
                }
            }
            Map<String, Manifest.FileInfo> files = new TreeMap<>();
            sources.forEach((name, source) -> files.put(name, source.info()));
            manifest = new Manifest(1, data.get("versionId").getAsString(),
                    dependencies.get("minecraft").getAsString(),
                    dependencies.get("neoforge").getAsString(), files).validate();
            if (!files.containsKey("mods/eldervale-updater.jar")) {
                throw new IOException("The modpack must contain mods/eldervale-updater.jar");
            }
            if (!files.containsKey("pack/pack.mcmeta")) {
                throw new IOException("Missing pack/pack.mcmeta. Include the Eldervale resource pack in overrides/pack.");
            }
        } catch (IOException exception) {
            archive.close();
            throw exception;
        } catch (RuntimeException exception) {
            archive.close();
            throw new IOException("Invalid .mrpack contents", exception);
        }
    }

    public Manifest manifest() {
        return manifest;
    }

    public Map<String, Source> sources() {
        return Map.copyOf(sources);
    }

    public void extract(Source source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (var input = archive.getInputStream(archive.getEntry(source.archiveEntry()));
             var output = Files.newOutputStream(target)) {
            transfer(input, output, null, source.info().size());
        }
        if (!Hashes.matches(target, source.info())) {
            throw new IOException("Corrupted archive file: " + source.archiveEntry());
        }
    }

    private static long transfer(InputStream input, java.io.OutputStream output,
                                 java.security.MessageDigest digest, long expected) throws IOException {
        byte[] buffer = new byte[131072];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Operation cancelled");
            }
            total += count;
            if (total > expected) {
                throw new IOException("ZIP entry exceeds its declared size");
            }
            if (output != null) {
                output.write(buffer, 0, count);
            }
            if (digest != null) {
                digest.update(buffer, 0, count);
            }
        }
        if (total != expected) {
            throw new IOException("Incomplete ZIP entry");
        }
        return total;
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        byte[] data = input.readNBytes(limit + 1);
        if (data.length > limit) {
            throw new IOException("JSON size limit exceeded");
        }
        return data;
    }

    @Override
    public void close() throws IOException {
        archive.close();
    }
}
