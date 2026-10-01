package dev.forge.build;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Copies declared inputs outside the project and verifies that no source changed during capture. */
public final class InputSnapshot {
    public final File directory;
    public final String sha256;
    public final Map<String, String> files;
    private InputSnapshot(File directory, Map<String, String> files) {
        this.directory = directory;
        this.files = Collections.unmodifiableMap(new TreeMap<>(files));
        this.sha256 = digest(files);
    }
    public static InputSnapshot capture(ProjectModel project, File destination) throws IOException {
        Path sourceRoot = project.root.getCanonicalFile().toPath();
        Path target = destination.getCanonicalFile().toPath();
        if (target.startsWith(sourceRoot)) throw new IOException("Snapshot destination must be outside the project");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Snapshot destination already exists");
        Map<String, String> before = hashInputs(project);
        Path parent = target.getParent();
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent)) throw new IOException("Snapshot parent must not be a symlink");
        Path temp = Files.createTempDirectory(parent, ".capture-");
        boolean moved = false;
        try {
            for (Map.Entry<String, String> input : before.entrySet()) {
                File source = WorkspaceFiles.resolve(project.root, input.getKey());
                File output = WorkspaceFiles.resolve(temp.toFile(), input.getKey());
                Files.createDirectories(output.toPath().getParent());
                Files.copy(source.toPath(), output.toPath());
                if (!WorkspaceFiles.sha256(output).equals(input.getValue()))
                    throw new IOException("Input changed while copying: " + input.getKey());
            }
            if (!before.equals(hashInputs(project))) throw new IOException("Project inputs changed during snapshot; retry after editing stops");
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            return new InputSnapshot(target.toFile(), before);
        } finally {
            if (!moved) deleteTemporaryTree(temp);
        }
    }
    public boolean stillMatches(ProjectModel project) throws IOException { return files.equals(hashInputs(project)); }

    public static Map<String, String> hashInputs(ProjectModel project) throws IOException {
        SortedSet<String> paths = new TreeSet<>();
        paths.add(project.manifest);
        for (String name : Arrays.asList(project.configuration, ".forge/project.json", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties"))
            if (WorkspaceFiles.resolve(project.root, name).isFile()) paths.add(name);
        for (List<String> roots : Arrays.asList(project.javaRoots, project.kotlinRoots, project.resourceRoots, project.assetRoots)) {
            for (String root : roots) for (File file : WorkspaceFiles.collect(project.root, root)) {
                paths.add(project.root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'));
            }
        }
        for (ProjectModel.Dependency dependency : project.dependencies) if (dependency.local) paths.add(dependency.value);
        if (paths.size() > 50000) throw new IOException("Project exceeds 50,000 input files");
        long total = 0;
        Map<String, String> hashes = new TreeMap<>();
        for (String path : paths) {
            File file = WorkspaceFiles.resolve(project.root, path);
            total += file.length();
            if (total > 512L * 1024 * 1024) throw new IOException("Project inputs exceed 512 MiB");
            hashes.put(path, WorkspaceFiles.sha256(file));
        }
        return hashes;
    }
    public static String digest(Map<String, String> values) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
                hash.update(entry.getKey().getBytes(StandardCharsets.UTF_8)); hash.update((byte) 0);
                hash.update(entry.getValue().getBytes(StandardCharsets.UTF_8)); hash.update((byte) 0);
            }
            return WorkspaceFiles.hex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    /** Only ever called for a directory returned by createTempDirectory above. Never follows links. */
    private static void deleteTemporaryTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
