package dev.forge.compiler;

public interface BuildListener {
    void log(String line);
    default void stage(String stage, boolean starting, long durationMs) { }
    default void diagnostic(String severity, String file, int line, int column, String message) { }
}
