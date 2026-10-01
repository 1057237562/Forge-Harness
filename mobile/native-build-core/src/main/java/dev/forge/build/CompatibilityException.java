package dev.forge.build;

import java.io.IOException;

/** Unsupported configuration is a build blocker, never silently treated as a successful import. */
public final class CompatibilityException extends IOException {
    public final String file;
    public final int line;
    public CompatibilityException(String file, int line, String reason) {
        super(file + (line > 0 ? ":" + line : "") + ": " + reason);
        this.file = file;
        this.line = line;
    }
}
