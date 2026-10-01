package dev.forge.gradle;
import org.objectweb.asm.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Index runtime-visible injection methods, avoiding ART reflection on unrelated desktop signatures. */
final class AndroidKotlinTransform {
    static void transform(File input, File output) {
        try {
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input.toPath()))) hash.append(String.format("%02x", b & 255));
            if (!hash.toString().equals("e71ff19e6b141ab85a9328fd010941531a302543026bd4244c95adc208d501f6")) throw new IOException("Unexpected Kotlin compiler artifact");
            StringBuilder index = new StringBuilder(); int[] patched = {0}, builtinsPatched = {0};
            try (ZipFile source = new ZipFile(input); ZipOutputStream target = new ZipOutputStream(new FileOutputStream(output))) {
                List<? extends ZipEntry> entries = Collections.list(source.entries()); entries.sort(Comparator.comparing(ZipEntry::getName));
                for (ZipEntry entry : entries) {
                    byte[] data; try (InputStream in = source.getInputStream(entry)) { data = in.readAllBytes(); }
                    if (entry.getName().endsWith(".class")) {
                        ClassReader reader = new ClassReader(data); String owner = reader.getClassName().replace('/', '.');
                        index.append("C\t").append(owner).append('\n');
                        reader.accept(new ClassVisitor(Opcodes.ASM9) {
                            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                                if ((access & Opcodes.ACC_PUBLIC) == 0 || name.startsWith("<")) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                                        if (visible && annotation.endsWith("/Inject;")) index.append("M\t").append(owner).append('\t').append(name).append('\t').append(descriptor).append('\n');
                                        return null;
                                    }
                                };
                            }
                        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        if (entry.getName().equals("org/jetbrains/kotlin/container/CacheKt.class")) {
                            ClassWriter writer = new ClassWriter(0);
                            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                                    MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
                                    if (!name.equals("getSetterInfos")) return base;
                                    return new MethodVisitor(Opcodes.ASM9, base) {
                                        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                                            if (owner.equals("java/lang/Class") && name.equals("getMethods") && desc.equals("()[Ljava/lang/reflect/Method;")) {
                                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/forge/kotlin/AndroidInjectionMethods", "getMethods", "(Ljava/lang/Class;)[Ljava/lang/reflect/Method;", false); patched[0]++;
                                            } else super.visitMethodInsn(opcode, owner, name, desc, isInterface);
                                        }
                                    };
                                }
                            }, 0); data = writer.toByteArray();
                        }
                        if (entry.getName().equals("org/jetbrains/kotlin/serialization/deserialization/builtins/BuiltInsResourceLoader.class")) {
                            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                                    MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
                                    if (!name.equals("loadResource") || !descriptor.equals("(Ljava/lang/String;)Ljava/io/InputStream;")) return base;
                                    return new MethodVisitor(Opcodes.ASM9, base) {
                                        @Override public void visitCode() {
                                            super.visitCode();
                                            super.visitLdcInsn("META-INF/forge-kotlin-builtins/");
                                            super.visitVarInsn(Opcodes.ALOAD, 1);
                                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
                                            super.visitVarInsn(Opcodes.ASTORE, 1); builtinsPatched[0]++;
                                        }
                                    };
                                }
                            }, 0); data = writer.toByteArray();
                        }
                    }
                    String outputName = entry.getName().endsWith(".kotlin_builtins") ? "META-INF/forge-kotlin-builtins/" + entry.getName() : entry.getName();
                    ZipEntry copy = new ZipEntry(outputName); copy.setTime(0); target.putNextEntry(copy); target.write(data); target.closeEntry();
                }
                target.putNextEntry(new ZipEntry("META-INF/forge-kotlin-injection-index.txt"));
                target.write(index.toString().getBytes(StandardCharsets.UTF_8)); target.closeEntry();
            }
            if (patched[0] != 1) throw new IOException("Kotlin injection adapter patch count changed: " + patched[0]);
            if (builtinsPatched[0] != 1) throw new IOException("Kotlin builtin loader patch count changed: " + builtinsPatched[0]);
        } catch (Exception error) { throw new RuntimeException("Kotlin ART transform failed", error); }
    }
}
