package dev.holmzi.eldervale.updater.helper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import dev.holmzi.eldervale.updater.core.Hashes;
import dev.holmzi.eldervale.updater.core.JsonFiles;
import dev.holmzi.eldervale.updater.core.Manifest;
import dev.holmzi.eldervale.updater.core.Progress;
import dev.holmzi.eldervale.updater.core.SafeFiles;
import dev.holmzi.eldervale.updater.core.Versions;

final class Installer {
    record Change(String path, boolean existed, boolean remove, String backupHash) {}
    record Journal(boolean committed, Manifest previous, Manifest target, List<Change> changes) {}

    private final Path game;
    private final Path updater;
    private final Progress progress;

    Installer(Path game, Progress progress) throws IOException {
        this.game = game.toRealPath();
        updater = SafeFiles.resolve(this.game, "updater");
        this.progress = progress;
    }

    String install() throws IOException {
        Path journalPath = own("journal.json");
        if (Files.exists(journalPath)) {
            Journal journal = JsonFiles.read(journalPath, Journal.class);
            validateJournal(journal);
            if (journal.committed()) {
                cleanup();
                return journal.target().version();
            }
            progress.update("Восстановление прерванной установки", 0);
            rollback(journal);
        }
        Manifest previous = JsonFiles.read(own("state.json"), Manifest.class).validate();
        Manifest target = JsonFiles.read(own("pending.json"), Manifest.class).validate();
        if (Versions.compare(target.version(), previous.version()) <= 0
                || !previous.minecraft().equals(target.minecraft())
                || !previous.neoforge().equals(target.neoforge())) {
            throw new IOException("Update does not match the installed modpack");
        }
        var union = new LinkedHashSet<>(previous.files().keySet());
        union.addAll(target.files().keySet());
        SafeFiles.checkCollisions(union);
        List<Change> changes = new ArrayList<>();
        int checked = 0;
        for (String name : union) {
            Path current = SafeFiles.resolve(game, name);
            boolean existed = Files.exists(current, LinkOption.NOFOLLOW_LINKS);
            if (existed && !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Expected a regular file: " + name);
            }
            Manifest.FileInfo expected = target.files().get(name);
            if (expected != null) {
                if (!Hashes.matches(own("staging/" + name), expected)) {
                    throw new IOException("Corrupted staged file: " + name);
                }
                if (Hashes.matches(current, expected)) {
                    checked++;
                    continue;
                }
                if (existed && !previous.files().containsKey(name) && !name.startsWith("config/")) {
                    throw new IOException("New file conflicts with an untracked file: " + name);
                }
            } else if (!existed) {
                checked++;
                continue;
            }
            changes.add(new Change(name, existed, expected == null,
                    existed ? Hashes.hash(current, "SHA-512") : null));
            progress.update("Проверка файлов", 0.25 * ++checked / Math.max(1, union.size()));
        }
        SafeFiles.deleteWorkspace(updater, "backup");
        int backedUp = 0;
        for (Change change : changes) {
            if (change.existed()) {
                Path backup = own("backup/" + change.path());
                SafeFiles.atomicCopy(SafeFiles.resolve(game, change.path()), backup);
                if (!Hashes.hash(backup, "SHA-512").equals(change.backupHash())) {
                    throw new IOException("Backup verification failed: " + change.path());
                }
            }
            progress.update("Создание резервной копии", 0.25 + 0.2 * ++backedUp / Math.max(1, changes.size()));
        }
        Journal journal = new Journal(false, previous, target, changes);
        JsonFiles.write(journalPath, journal);
        try {
            int applied = 0;
            for (Change change : changes) {
                Path current = SafeFiles.resolve(game, change.path());
                if (change.remove()) {
                    Files.deleteIfExists(current);
                } else {
                    SafeFiles.atomicCopy(own("staging/" + change.path()), current);
                    if (!Hashes.matches(current, target.files().get(change.path()))) {
                        throw new IOException("Installed file verification failed: " + change.path());
                    }
                }
                progress.update((change.remove() ? "Удаление устаревших файлов" : "Установка файлов"),
                        0.45 + 0.5 * ++applied / Math.max(1, changes.size()));
            }
            JsonFiles.write(own("state.json"), target.sorted());
            JsonFiles.write(journalPath, new Journal(true, previous, target, changes));
        } catch (IOException | RuntimeException exception) {
            try {
                progress.update("Восстановление предыдущей версии", 0);
                rollback(journal);
            } catch (Exception recoveryFailure) {
                exception.addSuppressed(recoveryFailure);
                throw new IOException("Rollback could not be completed. Do not start the game; "
                        + "resolve the issue and run updater.jar again.", exception);
            }
            throw new IOException("Installation cancelled; previous files restored. "
                    + exception.getMessage(), exception);
        }
        progress.update("Удаление временных файлов", 0.98);
        cleanup();
        return target.version();
    }

    private void validateJournal(Journal journal) throws IOException {
        if (journal == null || journal.previous() == null || journal.target() == null
                || journal.changes() == null || journal.changes().size() > 100_000) {
            throw new IOException("Invalid recovery journal");
        }
        journal.previous().validate();
        journal.target().validate();
        var names = new LinkedHashSet<String>();
        for (Change change : journal.changes()) {
            SafeFiles.requireManaged(change.path());
            if (!names.add(change.path())
                    || !journal.previous().files().containsKey(change.path())
                    && !journal.target().files().containsKey(change.path())
                    || change.existed() && (change.backupHash() == null
                    || !change.backupHash().matches("[a-f0-9]{128}"))) {
                throw new IOException("Invalid recovery file list");
            }
        }
        SafeFiles.checkCollisions(names);
    }

    private void rollback(Journal journal) throws IOException {
        validateJournal(journal);
        for (Change change : journal.changes()) {
            if (change.existed()
                    && !Hashes.hash(own("backup/" + change.path()), "SHA-512").equals(change.backupHash())) {
                throw new IOException("Corrupted backup: " + change.path());
            }
        }
        for (Change change : journal.changes()) {
            Path target = SafeFiles.resolve(game, change.path());
            if (change.existed()) {
                SafeFiles.atomicCopy(own("backup/" + change.path()), target);
            } else {
                Files.deleteIfExists(target);
            }
        }
        JsonFiles.write(own("state.json"), journal.previous().sorted());
        Files.deleteIfExists(own("journal.json"));
        SafeFiles.deleteWorkspace(updater, "backup");
    }

    private void cleanup() throws IOException {
        SafeFiles.deleteWorkspace(updater, "downloads");
        SafeFiles.deleteWorkspace(updater, "staging");
        SafeFiles.deleteWorkspace(updater, "backup");
        Files.deleteIfExists(own("pending.json"));
        Files.deleteIfExists(own("required-release.json"));
        Files.deleteIfExists(own("journal.json"));
    }

    private Path own(String relative) throws IOException {
        return SafeFiles.resolve(updater, relative);
    }
}
