package dev.holmzi.eldervale.updater.core;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

public record Manifest(int schema, String version, String minecraft, String neoforge,
                       Map<String, FileInfo> files) {
    public record FileInfo(String sha512, long size) {}

    public Manifest validate() throws IOException {
        if (schema != 1 || files == null || files.size() > 100_000
                || minecraft == null || neoforge == null || neoforge.isBlank()) {
            throw new IOException("Invalid modpack manifest");
        }
        Versions.parse(version);
        if (!"1.21.1".equals(minecraft)) {
            throw new IOException("Unsupported base game version");
        }
        for (var entry : files.entrySet()) {
            SafeFiles.requireManaged(entry.getKey());
            FileInfo info = entry.getValue();
            if (info == null || info.sha512() == null
                    || !info.sha512().matches("[a-fA-F0-9]{128}")
                    || info.size() < 0 || info.size() > 4L * 1024 * 1024 * 1024) {
                throw new IOException("Invalid hash or size: " + entry.getKey());
            }
        }
        SafeFiles.checkCollisions(files.keySet());
        return this;
    }

    public Manifest sorted() {
        return new Manifest(schema, version, minecraft, neoforge, new TreeMap<>(files));
    }
}
