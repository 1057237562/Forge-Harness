package dev.forge.build;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.json.JSONObject;

/** App-private recovery records, separate from workspace inputs and Git changes. */
public final class EditorDraftStore {
    public static final class Draft {
        public final String baseHash, text;
        Draft(String hash, String text) { baseHash = hash; this.text = text; }
    }
    private final File directory;
    private final Map<String, Long> revisions = new HashMap<>();
    public EditorDraftStore(File directory) { this.directory = directory; }
    private String key(String project, String path) throws IOException {
        if (project == null || path == null || project.isEmpty() || path.isEmpty()) throw new IOException("Draft identity is required");
        try { return WorkspaceFiles.hex(MessageDigest.getInstance("SHA-256").digest((project + "\0" + path).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    public synchronized Draft load(String project, String path) throws IOException {
        File file = WorkspaceFiles.resolve(directory, key(project, path) + ".json");
        if (!file.isFile()) return null;
        try {
            JSONObject json = new JSONObject(WorkspaceFiles.readUtf8(file, 4 * 1024 * 1024));
            if (json.getInt("schemaVersion") != 1 || !json.getString("project").equals(project) || !json.getString("path").equals(path)) throw new IOException("Draft identity mismatch");
            String hash = json.getString("baseHash"), text = json.getString("text");
            if (!hash.matches("[a-f0-9]{64}") || text.getBytes(StandardCharsets.UTF_8).length > WorkspaceTextFile.MAX_BYTES) throw new IOException("Invalid recovery draft");
            return new Draft(hash, text);
        } catch (org.json.JSONException error) { throw new IOException("Invalid recovery draft", error); }
    }
    public synchronized void update(String project, String path, String hash, String text, long revision) throws IOException {
        String key = key(project, path);
        if (revision <= revisions.getOrDefault(key, -1L)) return;
        if (text != null && (hash == null || !hash.matches("[a-f0-9]{64}") || text.getBytes(StandardCharsets.UTF_8).length > WorkspaceTextFile.MAX_BYTES))
            throw new IOException("Draft exceeds the editor limit or has an invalid base version");
        File file = WorkspaceFiles.resolve(directory, key + ".json");
        if (text == null) Files.deleteIfExists(file.toPath());
        else {
            JSONObject json = new JSONObject().put("schemaVersion", 1).put("project", project).put("path", path).put("baseHash", hash).put("text", text);
            WorkspaceFiles.atomicWrite(file, json.toString().getBytes(StandardCharsets.UTF_8));
        }
        revisions.put(key, revision);
    }
}
