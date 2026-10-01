package dev.forge.build;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

public class NativeCompatibilityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File legacy() throws Exception {
        File root = temp.newFolder();
        Files.write(new File(root, "AndroidManifest.xml").toPath(), "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"dev.forge.legacy\"><uses-sdk android:minSdkVersion=\"28\" android:targetSdkVersion=\"29\"/><application/></manifest>".getBytes(StandardCharsets.UTF_8));
        new File(root, "src").mkdirs(); Files.write(new File(root, "src/A.java").toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));
        return root;
    }
    @Test public void previewsThenMigratesWithoutChangingSource() throws Exception {
        File root = legacy(), source = new File(root, "src/A.java"); String hash = WorkspaceFiles.sha256(source);
        NativeCompatibility.Report report = NativeCompatibility.inspect(root, 29);
        assertTrue(report.summary, report.configurationSupported); assertTrue(report.profileMatches); assertNotNull(report.migrationJson);
        assertFalse(new File(root, ".forge/project.json").exists());
        NativeCompatibility.apply(report);
        assertEquals(hash, WorkspaceFiles.sha256(source));
        ProjectModel migrated = new ProjectInspector().inspect(root); assertEquals("dev.forge.legacy", migrated.applicationId);
        assertEquals(".forge/project.json", migrated.configuration);
        assertNull(NativeCompatibility.inspect(root, 29).migrationJson);
        try { NativeCompatibility.apply(report); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("already exists")); }
    }
    @Test public void rejectsStalePreviewAndProfileMismatch() throws Exception {
        File root = legacy(); NativeCompatibility.Report report = NativeCompatibility.inspect(root, 29);
        Files.write(new File(root, "src/A.java").toPath(), "class Changed {}".getBytes(StandardCharsets.UTF_8));
        try { NativeCompatibility.apply(report); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("changed")); }
        NativeCompatibility.Report otherSdk = NativeCompatibility.inspect(root, 35);
        assertFalse(otherSdk.profileMatches);
        try { NativeCompatibility.apply(otherSdk); fail(); } catch (IOException expected) { }
        assertFalse(new File(root, ".forge/project.json").exists());
    }
    @Test public void unsupportedProjectHasNoMigrationThatHidesItsProblems() throws Exception {
        NativeCompatibility.Report report = NativeCompatibility.inspect(temp.newFolder(), 29);
        assertFalse(report.configurationSupported); assertNull(report.migrationJson); assertTrue(report.summary.contains("manifest"));
    }
}
