package dev.forge.build;

import java.io.IOException;
import java.util.*;

/** Rejects ambiguous archive names before extraction can overwrite an earlier entry. */
public final class ArchivePaths {
    private final Map<String, String> spellings = new HashMap<>();
    private final Set<String> files = new HashSet<>(), explicit = new HashSet<>();
    public String accept(String raw, boolean directory) throws IOException {
        if (raw == null || raw.isEmpty() || raw.startsWith("/") || raw.indexOf('\\') >= 0 || raw.indexOf(':') >= 0 || raw.indexOf('\0') >= 0)
            throw new IOException("Unsafe ZIP path: " + raw);
        String path = directory && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        String[] parts = path.split("/", -1); StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe ZIP path: " + raw);
            if (prefix.length() > 0) prefix.append('/'); prefix.append(part);
            String spelling = prefix.toString(), key = spelling.toLowerCase(Locale.ROOT);
            String previous = spellings.putIfAbsent(key, spelling);
            if (previous != null && !previous.equals(spelling)) throw new IOException("Case-ambiguous ZIP path: " + raw);
            if (i < parts.length - 1) {
                if (files.contains(key)) throw new IOException("ZIP file is also used as a directory: " + raw);
            } else {
                if (!explicit.add(key) || (!directory && previous != null)) throw new IOException("Duplicate or conflicting ZIP path: " + raw);
                if (!directory) files.add(key);
            }
        }
        return path;
    }
}
