package dev.forge.compiler;
import org.junit.Test;
import org.objectweb.asm.*;
import static org.junit.Assert.*;

public class ClassStructureHashTest {
    private byte[] clazz(int bodyValue, int constant, boolean addedMethod) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Value", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CONSTANT", "I", null, constant).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode(); method.visitLdcInsn(bodyValue); method.visitInsn(Opcodes.IRETURN); method.visitMaxs(1,0); method.visitEnd();
        if (addedMethod) writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "added", "()V", null, null).visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    @Test public void ignoresOnlyMethodCodeAndRetainsStructuralChanges() throws Exception {
        String first = ClassStructureHash.bytes(clazz(1, 4, false));
        assertEquals(first, ClassStructureHash.bytes(clazz(2, 4, false)));
        assertNotEquals(first, ClassStructureHash.bytes(clazz(1, 5, false)));
        assertNotEquals(first, ClassStructureHash.bytes(clazz(1, 4, true)));
    }
    private byte[] hierarchy(String parent, String annotation) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Value", null, "java/lang/Object", new String[]{parent});
        AnnotationVisitor value = writer.visitAnnotation("Ltest/Marker;", true); value.visit("value", annotation); value.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    @Test public void hierarchyAndAnnotationsInvalidateDependencyDex() throws Exception {
        String first = ClassStructureHash.bytes(hierarchy("test/First", "one"));
        assertNotEquals(first, ClassStructureHash.bytes(hierarchy("test/Second", "one")));
        assertNotEquals(first, ClassStructureHash.bytes(hierarchy("test/First", "two")));
    }
}
