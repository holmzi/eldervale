package dev.holmzi.eldervale.updater.helper;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import dev.holmzi.eldervale.updater.core.JsonFiles;
import dev.holmzi.eldervale.updater.core.Manifest;
import dev.holmzi.eldervale.updater.core.Mrpack;
import dev.holmzi.eldervale.updater.core.SafeFiles;
import dev.holmzi.eldervale.updater.core.Versions;

final class PackPreparer {
    private PackPreparer() {}

    static void prepare(Path input, Path output) throws IOException {
        prepare(input, output, null, null);
    }

    static void prepare(Path input, Path output, String tag, Path previous) throws IOException {
        input = input.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        if (input.equals(output) || Files.exists(output)) {
            throw new IOException("Choose a new output .mrpack path different from the input");
        }
        Manifest manifest;
        try (var pack = new Mrpack(input)) {
            manifest = pack.manifest().sorted();
        }
        if (tag != null && Versions.compare(tag, manifest.version()) != 0) {
            throw new IOException("Release tag does not match modrinth.index.json versionId");
        }
        if (previous != null) {
            try (var pack = new Mrpack(previous)) {
                Manifest installed = pack.manifest();
                if (Versions.compare(manifest.version(), installed.version()) <= 0) {
                    throw new IOException("The new version must be newer than the published release");
                }
                if (!manifest.minecraft().equals(installed.minecraft())
                        || !manifest.neoforge().equals(installed.neoforge())) {
                    throw new IOException("Minecraft and NeoForge versions must not change between releases");
                }
            }
        }
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), "prepared-", ".mrpack");
        boolean success = false;
        try {
            try (var source = new ZipFile(input.toFile(), StandardCharsets.UTF_8);
                 var target = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                var entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith("overrides/updater/") || name.startsWith("client-overrides/updater/")
                            || name.startsWith("server-overrides/updater/")) {
                        continue;
                    }
                    target.putNextEntry(preparedEntry(name));
                    if (name.equals("modrinth.index.json")) {
                        try (var content = source.getInputStream(entry)) {
                            var index = JsonParser.parseString(new String(content.readAllBytes(), StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                            JsonArray kept = new JsonArray();
                            for (var file : index.getAsJsonArray("files")) {
                                String path = file.getAsJsonObject().get("path").getAsString();
                                if (!path.startsWith("updater/")) {
                                    kept.add(file);
                                }
                            }
                            index.add("files", kept);
                            target.write(JsonFiles.GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
                        }
                    } else if (!entry.isDirectory()) {
                        try (var content = source.getInputStream(entry)) {
                            content.transferTo(target);
                        }
                    }
                    target.closeEntry();
                }
                target.putNextEntry(preparedEntry("overrides/updater/state.json"));
                target.write(JsonFiles.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
                target.closeEntry();
            }
            SafeFiles.move(temporary, output);
            success = true;
            System.out.println("Created: " + output);
            System.out.println("GitHub tag: " + manifest.version() + "; managed files: " + manifest.files().size());
        } finally {
            if (!success) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static ZipEntry preparedEntry(String name) {
        ZipEntry entry = new ZipEntry(name);
        // Re-preparing the same export must produce the same bytes, including after a CI retry.
        entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
        return entry;
    }
}
