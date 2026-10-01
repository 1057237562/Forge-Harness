package dev.forge.compiler;

import dev.forge.build.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Content-addressed, verified directory artifacts. Invalid cache data is a miss, never compiler output. */
final class StageCache {
    private final File root;
    private final Cancellation cancellation;
    private final long quotaBytes;
    StageCache(File root, Cancellation cancellation) { this(root, cancellation, 512L * 1024 * 1024); }
    StageCache(File root, Cancellation cancellation, long quotaBytes) {
        if (quotaBytes < 0) throw new IllegalArgumentException("Negative cache quota");
        this.root = root; this.cancellation = cancellation; this.quotaBytes = quotaBytes;
    }
    private static void validateKey(String key) throws IOException {
        if (key == null || !key.matches("[a-f0-9]{64}")) throw new IOException("Invalid stage cache key");
    }
    boolean restore(String key, File destination) throws IOException {
        validateKey(key);
        File archive = WorkspaceFiles.resolve(root, key + ".zip"), checksum = WorkspaceFiles.resolve(root, key + ".sha256");
        if (!archive.isFile() || !checksum.isFile()) return false;
        if (destination.list() == null || destination.list().length != 0) throw new IOException("Stage restore requires an empty output directory");
        Path temporary = Files.createTempDirectory(destination.toPath().getParent(), "stage-restore-");
        try {
            if (!WorkspaceFiles.readUtf8(checksum, 128).trim().equals(WorkspaceFiles.sha256(archive))) return false;
            long total = 0; int count = 0;
            try (ZipFile zip = new ZipFile(archive)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    cancellation.check(); ZipEntry entry = entries.nextElement();
                    if (++count > 20000 || entry.isDirectory() || Arrays.asList(entry.getName().split("/")).contains("..")) return false;
                    File target = WorkspaceFiles.resolve(temporary.toFile(), entry.getName());
                    Files.createDirectories(target.toPath().getParent());
                    try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW)) {
                        byte[] bytes = new byte[32768]; int n;
                        while ((n = input.read(bytes)) != -1) { cancellation.check(); total += n; if (total > 256L * 1024 * 1024) return false; output.write(bytes, 0, n); }
                    }
                }
            }
            if (count == 0) return false;
            Files.delete(destination.toPath());
            Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            archive.setLastModified(System.currentTimeMillis());
            return true;
        } catch (IOException invalid) {
            cancellation.check();
            Files.createDirectories(destination.toPath());
            return false;
        } finally { removeTemporary(temporary); }
    }
    void store(String key, File directory) throws IOException {
        validateKey(key);
        List<File> files = WorkspaceFiles.collect(directory, ".");
        if (files.isEmpty() || files.size() > 20000) return;
        long expanded = 0;
        for (File file : files) { expanded += file.length(); if (expanded > 256L * 1024 * 1024) return; }
        Files.createDirectories(root.toPath());
        Path temporary = Files.createTempFile(root.toPath(), "stage-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                byte[] bytes = new byte[32768];
                for (File file : files) {
                    cancellation.check();
                    ZipEntry entry = new ZipEntry(directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'));
                    entry.setTime(0); zip.putNextEntry(entry);
                    try (InputStream input = new FileInputStream(file)) { int n; while ((n = input.read(bytes)) != -1) { cancellation.check(); zip.write(bytes, 0, n); } }
                    zip.closeEntry();
                }
            }
            if (Files.size(temporary) + 64 > quotaBytes) { trim(null); return; }
            String hash = WorkspaceFiles.sha256(temporary.toFile());
            Files.move(temporary, WorkspaceFiles.resolve(root, key + ".zip").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            WorkspaceFiles.atomicWrite(WorkspaceFiles.resolve(root, key + ".sha256"), hash.getBytes(StandardCharsets.UTF_8));
            trim(key);
        } finally { Files.deleteIfExists(temporary); }
    }
    /** Only complete stage entries are evicted. Maven dependencies and other directories are untouched. */
    private void trim(String newestKey) throws IOException {
        File[] children = root.listFiles(); if (children == null) return;
        List<File> archives = new ArrayList<>(); long total = 0;
        for (File file : children) {
            cancellation.check();
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            if (file.getName().matches("stage-[0-9]+\\.tmp") && file.lastModified() < System.currentTimeMillis() - 24L * 60 * 60 * 1000) {
                Files.deleteIfExists(WorkspaceFiles.resolve(root, file.getName()).toPath()); continue;
            }
            if (file.getName().matches("[a-f0-9]{64}\\.sha256") && !new File(root, file.getName().replace(".sha256", ".zip")).exists()) {
                Files.deleteIfExists(WorkspaceFiles.resolve(root, file.getName()).toPath()); continue;
            }
            if (!file.getName().matches("[a-f0-9]{64}\\.zip") || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            File checksum = WorkspaceFiles.resolve(root, file.getName().replace(".zip", ".sha256"));
            if (!checksum.exists()) { Files.deleteIfExists(WorkspaceFiles.resolve(root, file.getName()).toPath()); continue; }
            if (!Files.isRegularFile(checksum.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            archives.add(file); total += file.length() + checksum.length();
        }
        archives.sort(Comparator.comparingLong(File::lastModified)
            .thenComparing(file -> file.getName().equals(newestKey + ".zip"))
            .thenComparing(File::getName));
        for (File archive : archives) {
            if (total <= quotaBytes) break;
            cancellation.check();
            File verified = WorkspaceFiles.resolve(root, archive.getName());
            File checksum = WorkspaceFiles.resolve(root, archive.getName().replace(".zip", ".sha256"));
            long removed = verified.length() + checksum.length();
            Files.deleteIfExists(verified.toPath()); Files.deleteIfExists(checksum.toPath()); total -= removed;
        }
    }
    private static void removeTemporary(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }
}
