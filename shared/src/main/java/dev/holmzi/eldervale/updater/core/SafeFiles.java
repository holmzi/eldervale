package dev.holmzi.eldervale.updater.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SafeFiles {
    public static final Set<String> ROOTS = Set.of("mods", "pack", "config", "shaderpacks");
    private static final LinkOption[] NO_LINKS = {LinkOption.NOFOLLOW_LINKS};

    private SafeFiles() {}

    public static boolean managed(String name) {
        return name != null && name.contains("/") && ROOTS.contains(name.substring(0, name.indexOf('/')));
    }

    public static void validateName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 1024
                || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            throw new IOException("Invalid path: " + name);
        }
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")
                    || part.endsWith(".") || part.endsWith(" ")
                    || part.matches(".*[\\x00-\\x1f<>\"|?*].*")
                    || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
                throw new IOException("Invalid path: " + name);
            }
        }
    }

    public static void requireManaged(String name) throws IOException {
        validateName(name);
        if (!managed(name)) {
            throw new IOException("Path is outside managed directories: " + name);
        }
    }

    public static void checkCollisions(Collection<String> names) throws IOException {
        Set<String> folded = new HashSet<>();
        for (String name : names) {
            if (!folded.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Case-insensitive path collision: " + name);
            }
        }
        for (String name : folded) {
            int slash = name.lastIndexOf('/');
            while (slash >= 0) {
                if (folded.contains(name.substring(0, slash))) {
                    throw new IOException("File and directory path collision: " + name);
                }
                slash = name.lastIndexOf('/', slash - 1);
            }
        }
    }

    public static Path resolve(Path root, String relative) throws IOException {
        validateName(relative);
        Path base = root.toAbsolutePath().normalize();
        Path result = base.resolve(relative).normalize();
        if (!result.startsWith(base)) {
            throw new IOException("Path escapes the root directory");
        }
        rejectLinks(base);
        Path current = base;
        for (Path part : base.relativize(result)) {
            current = current.resolve(part);
            rejectLinks(current);
        }
        return result;
    }

    public static void rejectLinks(Path path) throws IOException {
        if (Files.exists(path, NO_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NO_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Symbolic link or special file is not supported: " + path);
            }
            if (attributes.isDirectory() && !path.toRealPath().equals(path.toAbsolutePath().normalize())) {
                throw new IOException("Redirected directory is not supported: " + path);
            }
        }
    }

    public static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".updater-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            move(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void atomicCopy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".updater-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void deleteWorkspace(Path updater, String child) throws IOException {
        Path target = resolve(updater, child);
        if (!Files.exists(target, NO_LINKS)) {
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                rejectLinks(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                rejectLinks(path);
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
