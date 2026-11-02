package dev.holmzi.eldervale.updater.core;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class Stager {
    private Stager() {}

    public static void prepare(Path game, Manifest installed, GitHub.Release release,
                               Progress progress) throws IOException {
        installed.validate();
        release.validate();
        Path updater = SafeFiles.resolve(game, "updater");
        Path archive = SafeFiles.resolve(updater, "downloads/update.mrpack");
        SafeFiles.deleteWorkspace(updater, "staging");
        Files.deleteIfExists(SafeFiles.resolve(updater, "pending.json"));
        Downloads.file(URI.create(release.url()), archive, release.size(),
                received -> progress.update("Скачивание сборки", 0.2 * received / release.size()));
        if (release.sha256() != null
                && !Hashes.hash(archive, "SHA-256").equalsIgnoreCase(release.sha256())) {
            throw new IOException("Контрольная сумма .mrpack не совпадает с GitHub");
        }
        progress.update("Чтение сборки", 0.2);
        try (var pack = new Mrpack(archive)) {
            Manifest target = pack.manifest();
            if (Versions.compare(target.version(), release.version()) != 0) {
                throw new IOException("Версия внутри .mrpack не совпадает с тегом GitHub");
            }
            if (!installed.minecraft().equals(target.minecraft())
                    || !installed.neoforge().equals(target.neoforge())) {
                throw new IOException("В релизе изменена версия Minecraft или NeoForge");
            }
            long total = target.files().values().stream().mapToLong(Manifest.FileInfo::size).sum();
            long available = Files.getFileStore(updater).getUsableSpace();
            if (available < total * 2 + 256L * 1024 * 1024) {
                throw new IOException("Недостаточно места для подготовки и резервной копии");
            }
            long completed = 0;
            for (var entry : pack.sources().entrySet()) {
                String name = entry.getKey();
                Mrpack.Source source = entry.getValue();
                Path staged = SafeFiles.resolve(updater, "staging/" + name);
                Path current = SafeFiles.resolve(game, name);
                Files.createDirectories(staged.getParent());
                long offset = completed;
                Progress fileProgress = (message, fraction) -> progress.update(message,
                        0.2 + 0.8 * (offset + fraction * source.info().size()) / Math.max(1, total));
                fileProgress.update("Подготовка файлов", 0);
                if (Hashes.matches(current, source.info())) {
                    Files.copy(current, staged, StandardCopyOption.REPLACE_EXISTING);
                    if (!Hashes.matches(staged, source.info())) {
                        obtain(pack, source, staged, name, fileProgress);
                    }
                } else {
                    obtain(pack, source, staged, name, fileProgress);
                }
                completed += source.info().size();
                fileProgress.update("Подготовка файлов", 1);
            }
            JsonFiles.write(SafeFiles.resolve(updater, "pending.json"), target.sorted());
            progress.update("Обновление готово к установке", 1);
        }
    }

    private static void obtain(Mrpack pack, Mrpack.Source source, Path staged,
                               String name, Progress progress) throws IOException {
        if (source.archiveEntry() != null) {
            pack.extract(source, staged);
            return;
        }
        IOException failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            for (URI url : source.downloads()) {
                try {
                    Downloads.file(url, staged, source.info().size(), received ->
                            progress.update("Скачивание файлов", (double) received / Math.max(1, source.info().size())));
                    if (!Hashes.matches(staged, source.info())) {
                        throw new IOException("Не совпадает SHA-512: " + name);
                    }
                    return;
                } catch (IOException exception) {
                    failure = exception;
                    if (Thread.currentThread().isInterrupted()) {
                        throw exception;
                    }
                }
            }
        }
        throw new IOException("Не удалось скачать " + name + ": "
                + (failure == null ? "нет источника" : failure.getMessage()), failure);
    }
}
