package dev.forge.build;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class AndroidProjectTemplateTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void kotlinTemplateDeclaresOnlyKotlinSources() throws Exception {
        File root = new File(temp.getRoot(), "kotlin");
        AndroidProjectTemplate.create(root, "Kotlin app", "dev.forge.template", true);
        ProjectModel model = new ProjectInspector().inspect(root);
        assertTrue(model.javaRoots.isEmpty());
        assertEquals(java.util.Collections.singletonList("src"), model.kotlinRoots);
        assertTrue(new File(root, "src/dev/forge/template/MainActivity.kt").isFile());
        assertFalse(new File(root, "src/dev/forge/template/MainActivity.java").exists());
    }
    @Test public void createsInspectableOfflineProjectWithEscapedLabel() throws Exception {
        File root = new File(temp.getRoot(), "project");
        AndroidProjectTemplate.create(root, "Alice's \"A&B\" <你好>", "dev.forge.template");
        ProjectModel model = new ProjectInspector().inspect(root);
        assertEquals("dev.forge.template", model.applicationId); assertTrue(model.dependencies.isEmpty());
        assertTrue(new File(root, "src/dev/forge/template/MainActivity.java").isFile());
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(root, "res/values/strings.xml"));
        assertFalse(new File(root, "gradlew").exists());
    }
    @Test public void neverOverwritesExistingDirectory() throws Exception {
        File root = temp.newFolder("existing"); File original = new File(root, "keep"); Files.write(original.toPath(), new byte[]{42});
        try { AndroidProjectTemplate.create(root, "App", "dev.forge.template"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("already exists")); }
        assertEquals(42, Files.readAllBytes(original.toPath())[0]); assertEquals(1, root.list().length);
    }
    @Test public void rejectsInvalidInputsBeforeCreatingFiles() throws Exception {
        File root = new File(temp.getRoot(), "invalid");
        try { AndroidProjectTemplate.create(root, "App", "../escape"); fail(); } catch (IOException expected) { }
        assertFalse(root.exists());
        try { AndroidProjectTemplate.create(root, "bad\u0000name", "dev.forge.template"); fail(); } catch (IOException expected) { }
        assertFalse(root.exists());
    }
}
