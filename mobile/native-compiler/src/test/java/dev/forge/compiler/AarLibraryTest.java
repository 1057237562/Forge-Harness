package dev.forge.compiler;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.Assert.*;

public class AarLibraryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File archive(String... pairs) throws Exception {
        File file = temp.newFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < pairs.length; i += 2) {
                out.putNextEntry(new ZipEntry(pairs[i])); out.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        return file;
    }
    private AarLibrary extract(File archive) throws Exception {
        return AarLibrary.extract(archive, new File(temp.getRoot(), "out"), 29, new Cancellation());
    }
    private static final String MANIFEST = "<manifest package=\"dev.forge.library\"/>";
    @Test public void extractsResourcesAndNestedJars() throws Exception {
        AarLibrary library = extract(archive("AndroidManifest.xml", MANIFEST, "res/values/x.xml", "<resources/>", "classes.jar", "fixture", "libs/nested.jar", "fixture"));
        assertEquals("dev.forge.library", library.packageName); assertEquals(2, library.jars.size());
        assertTrue(new File(library.directory, "res/values/x.xml").isFile());
    }
    @Test public void rejectsZipTraversalBeforeWritingOutsideOutput() throws Exception {
        try { extract(archive("../escape.txt", "bad")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unsafe")); }
        assertFalse(new File(temp.getRoot(), "escape.txt").exists());
    }
    @Test public void rejectsCaseAliasedEntries() throws Exception {
        try { extract(archive("assets/a.txt", "first", "assets/A.txt", "second")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Duplicate")); }
    }
    @Test public void rejectsUnsupportedCompileSdkBeforeResourceLinking() throws Exception {
        try { extract(archive("AndroidManifest.xml", MANIFEST, "META-INF/com/android/build/gradle/aar-metadata.properties", "minCompileSdk=35")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("requires compileSdk 35")); }
    }
    @Test public void rejectsExternalEntities() throws Exception {
        try { extract(archive("AndroidManifest.xml", "<!DOCTYPE manifest [<!ENTITY x SYSTEM 'file:///secret'>]><manifest package='dev.forge.lib'/>")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("entities")); }
    }
}
