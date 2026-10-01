package dev.forge.compiler;

import dev.forge.build.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Stream-based deterministic packaging; duplicate runtime resources are errors, not overwritten silently. */
final class ApkAssembler {
    static void pack(ProjectModel model, File resources, File dexDir, File classesDir, List<File> runtimeJars, List<AarLibrary> libraries, File output,
                     Cancellation token) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            try (ZipFile input = new ZipFile(resources)) {
                for (ZipEntry entry : sorted(input)) if (!entry.isDirectory()) {
                    token.check();
                    try (InputStream data = input.getInputStream(entry)) { put(zip, names, entry.getName(), data, entry.getSize(), entry.getCrc(), true); }
                }
            }
            File[] dexFiles = dexDir.listFiles((dir, name) -> name.endsWith(".dex"));
            if (dexFiles == null || dexFiles.length == 0) throw new IOException("No DEX output");
            Arrays.sort(dexFiles, Comparator.comparing(File::getName));
            for (File dex : dexFiles) { token.check(); addFile(zip, names, dex.getName(), dex); }
            for (File compiled : WorkspaceFiles.collect(classesDir, ".")) {
                String name = classesDir.toPath().relativize(compiled.toPath()).toString().replace(File.separatorChar, '/');
                if (!isRuntimeResource(name)) continue;
                token.check();
                addFile(zip, names, name, compiled);
            }
            Map<String, File> assets = new LinkedHashMap<>();
            Map<String, File> nativeLibraries = new LinkedHashMap<>();
            for (AarLibrary library : libraries) {
                File assetRoot = new File(library.directory, "assets");
                for (File asset : WorkspaceFiles.collect(library.directory, "assets"))
                    assets.put(assetRoot.toPath().relativize(asset.toPath()).toString().replace(File.separatorChar, '/'), asset);
                File jniRoot = new File(library.directory, "jni");
                for (File nativeFile : WorkspaceFiles.collect(library.directory, "jni")) {
                    String name = jniRoot.toPath().relativize(nativeFile.toPath()).toString().replace(File.separatorChar, '/');
                    if (!name.matches("(arm64-v8a|armeabi-v7a|x86|x86_64)/lib[^/]+\\.so")) throw new IOException("Unsupported AAR native library: " + name);
                    File previous = nativeLibraries.putIfAbsent(name, nativeFile);
                    if (previous != null && !WorkspaceFiles.sha256(previous).equals(WorkspaceFiles.sha256(nativeFile))) throw new IOException("Conflicting native library: " + name);
                }
            }
            for (String path : model.assetRoots) {
                File root = WorkspaceFiles.resolve(model.root, path);
                for (File asset : WorkspaceFiles.collect(model.root, path)) {
                    token.check();
                    assets.put(root.toPath().relativize(asset.toPath()).toString().replace(File.separatorChar, '/'), asset);
                }
            }
            for (Map.Entry<String, File> asset : assets.entrySet()) { token.check(); addFile(zip, names, "assets/" + asset.getKey(), asset.getValue()); }
            for (Map.Entry<String, File> nativeFile : nativeLibraries.entrySet()) { token.check(); addFile(zip, names, "lib/" + nativeFile.getKey(), nativeFile.getValue()); }
            for (File jar : runtimeJars) try (ZipFile input = new ZipFile(jar)) {
                for (ZipEntry entry : sorted(input)) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !isRuntimeResource(name)) continue;
                    token.check();
                    try (InputStream data = input.getInputStream(entry)) { put(zip, names, name, data, entry.getSize(), entry.getCrc(), false); }
                }
            }
        }
    }
    private static boolean isRuntimeResource(String name) {
        return !name.endsWith(".class") && !name.equals("META-INF/MANIFEST.MF") &&
            !name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)");
    }
    private static List<? extends ZipEntry> sorted(ZipFile zip) {
        List<? extends ZipEntry> result = Collections.list(zip.entries());
        result.sort(Comparator.comparing(ZipEntry::getName)); return result;
    }
    private static void addFile(ZipOutputStream zip, Set<String> names, String name, File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) { put(zip, names, name, input, file.length(), -1, false); }
    }
    private static void put(ZipOutputStream zip, Set<String> names, String name, InputStream data, long size,
                            long crc, boolean stored) throws IOException {
        if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 ||
            Arrays.asList(name.split("/")).contains("..")) throw new IOException("Unsafe APK entry: " + name);
        if (!names.add(name)) throw new IOException("Duplicate APK resource: " + name);
        ZipEntry entry = new ZipEntry(name); entry.setTime(0);
        if (stored) { entry.setMethod(ZipEntry.STORED); entry.setSize(size); entry.setCrc(crc); }
        zip.putNextEntry(entry);
        byte[] bytes = new byte[32768]; int count;
        while ((count = data.read(bytes)) != -1) zip.write(bytes, 0, count);
        zip.closeEntry();
    }
}
