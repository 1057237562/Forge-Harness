package dev.forge.compiler;

import dev.forge.build.ProjectModel;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class ApkAssemblerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void retainsCompilerResourcesButNotBytecodeOrSignatures() throws Exception {
        File classes = temp.newFolder("classes");
        write(classes, "META-INF/main.kotlin_module", new byte[]{1, 2, 3});
        write(classes, "dev/sample/Main.class", new byte[]{4});
        write(classes, "META-INF/OLD.RSA", new byte[]{5});
        File apk = pack(classes, Collections.emptyList());
        try (ZipFile zip = new ZipFile(apk)) {
            assertArrayEquals(new byte[]{1, 2, 3}, read(zip, "META-INF/main.kotlin_module"));
            assertNotNull(zip.getEntry("classes.dex"));
            assertNull(zip.getEntry("dev/sample/Main.class"));
            assertNull(zip.getEntry("META-INF/OLD.RSA"));
        }
    }

    @Test public void rejectsConflictingCompilerAndLibraryResources() throws Exception {
        File classes = temp.newFolder("classes");
        write(classes, "META-INF/main.kotlin_module", new byte[]{1});
        File jar = temp.newFile("library.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/main.kotlin_module"));
            zip.write(2); zip.closeEntry();
        }
        try { pack(classes, Collections.singletonList(jar)); fail("Resource collision must not silently overwrite"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Duplicate APK resource")); }
    }

    private File pack(File classes, List<File> jars) throws Exception {
        File resources = temp.newFile("resources.ap_");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(resources))) {}
        File dex = temp.newFolder("dex");
        write(dex, "classes.dex", new byte[]{6});
        ProjectModel model = new ProjectModel(temp.getRoot(), "sample", "dev.sample", "dev.sample",
            29, 21, 29, 1, "1", "AndroidManifest.xml", Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ".forge/project.json", Collections.emptyList());
        File apk = new File(temp.getRoot(), "output.apk");
        ApkAssembler.pack(model, resources, dex, classes, jars, Collections.emptyList(), apk, new Cancellation());
        return apk;
    }
    private static void write(File root, String path, byte[] bytes) throws Exception {
        File file = new File(root, path); file.getParentFile().mkdirs(); Files.write(file.toPath(), bytes);
    }
    private static byte[] read(ZipFile zip, String path) throws Exception {
        try (InputStream input = zip.getInputStream(zip.getEntry(path)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[128]; int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return output.toByteArray();
        }
    }
}
