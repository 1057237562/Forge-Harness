package dev.forge.compiler;
import dev.forge.build.*;
import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.util.*;

/** All non-code class structure, including private members, constants, annotations and hierarchy. */
final class ClassStructureHash {
    static String directory(File root, Cancellation token) throws IOException {
        Map<String, String> classes = new TreeMap<>();
        for (File file : WorkspaceFiles.collect(root, ".")) if (file.getName().endsWith(".class")) {
            token.check();
            classes.put(root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'), bytes(Files.readAllBytes(file.toPath())));
        }
        return InputSnapshot.digest(classes);
    }
    static String bytes(byte[] bytecode) throws IOException {
        try {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(bytecode).accept(writer, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return WorkspaceFiles.hex(MessageDigest.getInstance("SHA-256").digest(writer.toByteArray()));
        } catch (Exception error) { throw new IOException("Cannot fingerprint class structure", error); }
    }
}
