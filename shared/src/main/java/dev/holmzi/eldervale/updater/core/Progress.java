package dev.holmzi.eldervale.updater.core;

@FunctionalInterface
public interface Progress {
    void update(String message, double fraction);
}
