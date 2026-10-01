package dev.forge.build;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Shared path boundary for imports, snapshots, dependency paths and agent tools. */
public final class WorkspaceFiles {
    private WorkspaceFiles() {}
    public static File resolve(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.indexOf('\0') >= 0 ||
            relative.indexOf('\\') >= 0 || relative.startsWith("/") || relative.indexOf(':') >= 0)
            throw new IOException("Expected workspace-relative '/' path: " + relative);
        Path base = root.toPath().toAbsolutePath().normalize();
        Path result = base.resolve(relative).normalize();
        if (!result.startsWith(base)) throw new IOException("Path escapes workspace: " + relative);
        // Reject links on every component; a canonical-path check alone would allow internal links.
        Path current = base;
        if (Files.isSymbolicLink(base)) throw new IOException("Workspace root is a symlink");
        for (Path component : base.relativize(result)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Symlink not supported: " + relative);
        }
        File canonicalBase = root.getCanonicalFile();
        File canonical = result.toFile().getCanonicalFile();
        if (!canonical.toPath().startsWith(canonicalBase.toPath()))
            throw new IOException("Canonical path escapes workspace: " + relative);
        return canonical;
    }
    public static String readUtf8(File file, int maxBytes) throws IOException {
        if (file.length() > maxBytes) throw new IOException("File exceeds limit: " + file);
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > maxBytes) throw new IOException("File exceeds limit: " + file);
                out.write(buffer, 0, n);
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return text.startsWith("\ufeff") ? text.substring(1) : text;
        }
    }
    public static List<File> collect(File root, String relative) throws IOException {
        File start = resolve(root, relative);
        List<File> result = new ArrayList<>();
        if (start.exists()) visit(root.getCanonicalFile(), start, result);
        return result;
    }
    private static void visit(File root, File item, List<File> result) throws IOException {
        String relative = root.toPath().relativize(item.toPath()).toString().replace(File.separatorChar, '/');
        resolve(root, relative.isEmpty() ? "." : relative);
        if (item.isDirectory()) {
            File[] children = item.listFiles();
            if (children == null) throw new IOException("Cannot read directory: " + item);
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) visit(root, child, result);
        } else if (item.isFile()) result.add(item);
        else throw new IOException("Not a regular file: " + item);
    }
    public static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] bytes = new byte[32768]; int n;
                while ((n = in.read(bytes)) != -1) digest.update(bytes, 0, n);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    public static void atomicWrite(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create " + parent);
        Path temp = Files.createTempFile(parent.toPath(), file.getName(), ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
