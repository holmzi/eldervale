package dev.holmzi.eldervale.updater.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class GitHub {
    public static final String REPOSITORY = "holmzi/eldervale";
    private static final URI LATEST = URI.create("https://api.github.com/repos/" + REPOSITORY + "/releases/latest");

    public record Release(String version, String notes, String url, long size, String sha256) {
        public Release validate() throws IOException {
            Versions.parse(version);
            URI address;
            try {
                address = URI.create(url);
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IOException("Некорректный адрес релиза", exception);
            }
            Downloads.validateUrl(address);
            if (!"github.com".equalsIgnoreCase(address.getHost())
                    || !address.getPath().startsWith("/" + REPOSITORY + "/releases/download/")
                    || size <= 0 || size > 4L * 1024 * 1024 * 1024
                    || sha256 != null && !sha256.matches("[a-fA-F0-9]{64}")) {
                throw new IOException("Некорректный файл релиза");
            }
            return this;
        }
    }

    private GitHub() {}

    public static Release latest() throws IOException {
        try {
            JsonObject release = JsonParser.parseString(new String(Downloads.json(LATEST),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()) {
                throw new IOException("Релиз не опубликован как стабильный");
            }
            JsonObject asset = null;
            for (var element : release.getAsJsonArray("assets")) {
                JsonObject candidate = element.getAsJsonObject();
                if (candidate.get("name").getAsString().toLowerCase(java.util.Locale.ROOT).endsWith(".mrpack")) {
                    if (asset != null) {
                        throw new IOException("В релизе должно быть ровно одно вложение .mrpack");
                    }
                    asset = candidate;
                }
            }
            if (asset == null) {
                throw new IOException("В последнем релизе нет .mrpack");
            }
            String hash = null;
            if (asset.has("digest") && !asset.get("digest").isJsonNull()) {
                String digest = asset.get("digest").getAsString();
                if (!digest.startsWith("sha256:")) {
                    throw new IOException("Неизвестный тип контрольной суммы релиза");
                }
                hash = digest.substring(7);
            }
            String notes = release.has("body") && !release.get("body").isJsonNull()
                    ? release.get("body").getAsString() : "";
            return new Release(release.get("tag_name").getAsString(), notes,
                    asset.get("browser_download_url").getAsString(), asset.get("size").getAsLong(), hash).validate();
        } catch (RuntimeException exception) {
            throw new IOException("Не удалось прочитать ответ GitHub", exception);
        }
    }
}
