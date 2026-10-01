package dev.forge.gradle;

import org.gradle.api.artifacts.transform.*;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Changes only XML factory creation in the pinned Android tools common artifact. */
public abstract class AndroidXmlTransform implements TransformAction<TransformParameters.None> {
    @InputArtifact @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract Provider<FileSystemLocation> getInputArtifact();
    @Override public void transform(TransformOutputs outputs) {
        File input = getInputArtifact().get().getAsFile();
        if (input.getName().equals("kotlin-compiler-embeddable-1.9.24.jar")) {
            AndroidKotlinTransform.transform(input, outputs.file("kotlin-compiler-embeddable-1.9.24-art.jar")); return;
        }
        if (!input.getName().equals("common-30.0.3.jar")) { outputs.file(input); return; }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input.toPath()));
            StringBuilder hash = new StringBuilder(); for (byte b : digest) hash.append(String.format("%02x", b & 255));
            if (!hash.toString().equals("8751efaaf2c2ddd1f0a37526c794347def6a3057ca9fc510307c13a6cf0d036f"))
                throw new IOException("Unexpected Android common artifact; review XML adapter before upgrading");
            int[] replacements = {0};
            File output = outputs.file("common-30.0.3-android-xml.jar");
            try (ZipFile source = new ZipFile(input); ZipOutputStream target = new ZipOutputStream(new FileOutputStream(output))) {
                List<? extends ZipEntry> entries = Collections.list(source.entries());
                entries.sort(Comparator.comparing(ZipEntry::getName));
                for (ZipEntry entry : entries) {
                    byte[] data; try (InputStream stream = source.getInputStream(entry)) { data = stream.readAllBytes(); }
                    if (entry.getName().equals("com/android/utils/PositionXmlParser.class") || entry.getName().equals("com/android/utils/XmlUtils.class")) {
                        ClassReader reader = new ClassReader(data); ClassWriter writer = new ClassWriter(0);
                        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC && method.equals("newInstance") &&
                                            (owner.equals("javax/xml/parsers/SAXParserFactory") || owner.equals("javax/xml/parsers/DocumentBuilderFactory"))) {
                                            String replacement = owner.endsWith("SAXParserFactory") ? "sax" : "dom";
                                            super.visitMethodInsn(opcode, "dev/forge/compiler/ManifestXmlFactories", replacement, descriptor, false);
                                            replacements[0]++;
                                        } else super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                                    }
                                };
                            }
                        }, 0);
                        data = writer.toByteArray();
                    }
                    ZipEntry copy = new ZipEntry(entry.getName()); copy.setTime(0); target.putNextEntry(copy); target.write(data); target.closeEntry();
                }
            }
            if (replacements[0] == 0) throw new IOException("No XML factory sites transformed");
        } catch (Exception error) { throw new RuntimeException("Android XML compatibility transform failed", error); }
    }
}
