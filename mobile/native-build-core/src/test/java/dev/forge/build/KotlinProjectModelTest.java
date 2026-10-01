package dev.forge.build;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class KotlinProjectModelTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void snapshotsKotlinAndAllowsSharedSourceRoot() throws Exception {
        File root = new File(temp.getRoot(), "project"); AndroidProjectTemplate.create(root, "Kotlin", "dev.forge.kt");
        File config = new File(root, ".forge/project.json");
        JSONObject json = new JSONObject(WorkspaceFiles.readUtf8(config, 100000)).put("kotlin", new JSONArray().put("src"));
        Files.write(config.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        File source = new File(root, "src/Value.kt"); Files.write(source.toPath(), "class Value".getBytes(StandardCharsets.UTF_8));
        ProjectModel model = new ProjectInspector().inspect(root);
        assertEquals(model.javaRoots, model.kotlinRoots);
        InputSnapshot snapshot = InputSnapshot.capture(model, new File(temp.getRoot(), "snapshot"));
        assertTrue(new File(snapshot.directory, "src/Value.kt").isFile());
        Files.write(source.toPath(), "class Changed".getBytes(StandardCharsets.UTF_8));
        assertFalse(snapshot.stillMatches(model));
    }
    @Test public void allowsKotlinOnlyConfigurationButNeverScripts() throws Exception {
        File root = new File(temp.getRoot(), "project"); AndroidProjectTemplate.create(root, "Kotlin", "dev.forge.kt");
        File config = new File(root, ".forge/project.json");
        JSONObject json = new JSONObject(WorkspaceFiles.readUtf8(config, 100000)).put("java", new JSONArray()).put("kotlin", new JSONArray().put("src"));
        Files.write(config.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(1, new ProjectInspector().inspect(root).kotlinRoots.size());
        Files.write(new File(root, "src/code.kts").toPath(), "println(1)".getBytes(StandardCharsets.UTF_8));
        try { new ProjectInspector().inspect(root); fail(); } catch (CompatibilityException expected) { assertTrue(expected.getMessage().contains("scripts")); }
    }
}
