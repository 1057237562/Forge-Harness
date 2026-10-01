package dev.forge.build;
import java.io.*;
import java.nio.file.*;

/** Converts compiler paths into workspace-relative editor locations without following links. */
public final class DiagnosticLocation {
    public final String path;
    public final int line;
    private DiagnosticLocation(String path, int line) { this.path = path; this.line = line; }
    public static DiagnosticLocation resolve(File workspace, String source, int line) throws IOException {
        if (source == null || source.isEmpty() || line < 1) throw new IOException("Diagnostic has no source location");
        Path base = workspace.toPath().toAbsolutePath().normalize();
        Path given = new File(source).toPath();
        Path absolute = (given.isAbsolute() ? given : base.resolve(given)).normalize();
        if (!absolute.startsWith(base) || absolute.equals(base)) throw new IOException("Diagnostic points outside this project");
        String relative = base.relativize(absolute).toString().replace(File.separatorChar, '/');
        File file = WorkspaceFiles.resolve(workspace, relative);
        if (!file.isFile()) throw new IOException("Diagnostic source no longer exists");
        return new DiagnosticLocation(relative, line);
    }
    public static int lineOffset(String text, int line) {
        if (line <= 1) return 0;
        int start = 0;
        for (int current = 1; current < line; current++) {
            int next = text.indexOf('\n', start);
            if (next < 0) return text.length();
            start = next + 1;
        }
        return start;
    }
}
