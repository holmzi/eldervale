package dev.holmzi.eldervale.updater.helper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import dev.holmzi.eldervale.updater.core.SafeFiles;

public final class Main {
    private Main() {}

    public static void main(String[] arguments) {
        if (arguments.length > 0 && arguments[0].equals("prepare")) {
            try {
                if (arguments.length < 3 || arguments.length > 5) {
                    throw new IOException("Usage: java -jar updater.jar prepare input.mrpack output.mrpack [tag [previous.mrpack]]");
                }
                PackPreparer.prepare(Path.of(arguments[1]), Path.of(arguments[2]),
                        arguments.length >= 4 ? arguments[3] : null,
                        arguments.length == 5 ? Path.of(arguments[4]) : null);
            } catch (Exception exception) {
                System.err.println(exception.getMessage());
                System.exit(1);
            }
            return;
        }
        ProgressWindow[] window = new ProgressWindow[1];
        try {
            SwingUtilities.invokeAndWait(() -> window[0] = new ProgressWindow());
            Path game = arguments.length == 0
                    ? Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent()
                    : Path.of(arguments[0]);
            game = game.toRealPath();
            Path updater = SafeFiles.resolve(game, "updater");
            Files.createDirectories(updater);
            if (arguments.length < 2) {
                var output = new PrintStream(Files.newOutputStream(SafeFiles.resolve(updater, "updater.log"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
                System.setOut(output);
                System.setErr(output);
            }
            if (arguments.length == 4) {
                if (!arguments[3].matches("ready-[a-f0-9-]{36}")) {
                    throw new IOException("Invalid launch token");
                }
                Path ready = SafeFiles.resolve(updater, arguments[3]);
                long parentId = Long.parseLong(arguments[1]);
                long started = Long.parseLong(arguments[2]);
                var parent = ProcessHandle.of(parentId);
                if (parent.isPresent()) {
                    long actual = parent.get().info().startInstant().orElseThrow().toEpochMilli();
                    if (actual != started) {
                        throw new IOException("The Eldervale process has changed");
                    }
                    SafeFiles.atomicWrite(ready, new byte[] {1});
                    parent.get().onExit().get(Duration.ofMinutes(5).toSeconds(), TimeUnit.SECONDS);
                } else {
                    SafeFiles.atomicWrite(ready, new byte[] {1});
                }
            } else if (arguments.length != 0 && arguments.length != 1) {
                throw new IOException("Invalid launch arguments");
            }
            try (FileChannel channel = FileChannel.open(SafeFiles.resolve(updater, "session.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("Close Eldervale and other updater instances, then run the updater again.");
                }
                try (lock) {
                    window[0].success(new Installer(game, window[0]::update).install());
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            if (window[0] != null) {
                String message = exception.getMessage();
                window[0].failure(message == null ? "Update failed" : message);
            } else {
                System.exit(1);
            }
        }
    }
}
