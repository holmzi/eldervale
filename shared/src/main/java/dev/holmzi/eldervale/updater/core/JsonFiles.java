package dev.holmzi.eldervale.updater.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonFiles() {}

    public static <T> T read(Path path, Class<T> type) throws IOException {
        if (Files.size(path) > 32L * 1024 * 1024) {
            throw new IOException("File is too large: " + path.getFileName());
        }
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T result = GSON.fromJson(reader, type);
            if (result == null) {
                throw new IOException("Empty JSON: " + path.getFileName());
            }
            return result;
        } catch (JsonParseException | IllegalArgumentException exception) {
            throw new IOException("Invalid JSON: " + path.getFileName(), exception);
        }
    }

    public static void write(Path path, Object value) throws IOException {
        SafeFiles.atomicWrite(path, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }
}
