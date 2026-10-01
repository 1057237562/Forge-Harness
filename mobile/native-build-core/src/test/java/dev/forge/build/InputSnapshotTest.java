package dev.forge.build;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

public class InputSnapshotTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File project;
    @Before public void setup() throws Exception {
        project = temporary.newFolder("legacy");
        write("AndroidManifest.xml", "<manifest package='dev.forge.test'><application/></manifest>");
        write("src/A.java", "class A {}\n");
        write("res/values/strings.xml", "<resources/>\n");
    }
    @Test public void snapshotIsIndependentAndDetectsContentChange() throws Exception {
        ProjectModel model = inspect();
        InputSnapshot snapshot = InputSnapshot.capture(model, new File(temporary.getRoot(), "snapshot"));
        assertTrue(snapshot.stillMatches(model));
        write("src/A.java", "class A { int changed; }\n");
        assertFalse(snapshot.stillMatches(model));
        assertEquals("class A {}\n", WorkspaceFiles.readUtf8(new File(snapshot.directory, "src/A.java"), 1024));
    }
    @Test public void additionsDeletesAndRenamesInvalidateSnapshot() throws Exception {
        ProjectModel model = inspect();
        InputSnapshot snapshot = InputSnapshot.capture(model, new File(temporary.getRoot(), "snapshot"));
        write("src/B.java", "class B {}");
        assertFalse(snapshot.stillMatches(model));
        Files.delete(new File(project, "src/B.java").toPath());
        assertTrue(snapshot.stillMatches(model));
        Files.move(new File(project, "src/A.java").toPath(), new File(project, "src/Renamed.java").toPath());
        assertFalse(snapshot.stillMatches(model));
    }
    @Test public void buildOutputsDoNotPolluteInputIdentity() throws Exception {
        ProjectModel model = inspect();
        InputSnapshot snapshot = InputSnapshot.capture(model, new File(temporary.getRoot(), "snapshot"));
        write("build/outputs/old.apk", "stale artifact");
        assertTrue(snapshot.stillMatches(model));
    }
    @Test public void cannotCaptureInsideProjectOrOverwriteDestination() throws Exception {
        try { InputSnapshot.capture(inspect(), new File(project, "build/snapshot")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("outside")); }
        File existing = temporary.newFolder("existing");
        try { InputSnapshot.capture(inspect(), existing); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("already exists")); }
    }
    @Test public void detectsConfigChangeAndKeepsStableDigestForSameInputs() throws Exception {
        ProjectModel model = inspect();
        InputSnapshot one = InputSnapshot.capture(model, new File(temporary.getRoot(), "one"));
        InputSnapshot two = InputSnapshot.capture(model, new File(temporary.getRoot(), "two"));
        assertEquals(one.sha256, two.sha256);
        write("gradle.properties", "android.useAndroidX=true\n");
        assertFalse(one.stillMatches(model));
    }
    @Test public void rejectsTraversalAndWindowsAbsolutePaths() throws Exception {
        for (String path : new String[]{"../outside", "C:/outside", "src\\A.java", "/absolute"}) {
            try { WorkspaceFiles.resolve(project, path); fail(path); } catch (IOException expected) { }
        }
    }
    @Test public void rejectsSymlinkInputWhenPlatformSupportsCreatingIt() throws Exception {
        Path link = new File(project, "src/linked.java").toPath();
        try { Files.createSymbolicLink(link, new File(project, "src/A.java").toPath()); }
        catch (IOException | UnsupportedOperationException e) { Assume.assumeNoException(e); }
        try { InputSnapshot.hashInputs(inspect()); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Symlink")); }
    }
    @Test public void newForgeConfigInvalidatesLegacySnapshot() throws Exception {
        ProjectModel model = inspect();
        InputSnapshot snapshot = InputSnapshot.capture(model, new File(temporary.getRoot(), "snapshot"));
        write(".forge/project.json", "{}");
        assertFalse(snapshot.stillMatches(model));
    }
    @Test public void newNonJavaSourceCannotEvadeSnapshotConsistencyCheck() throws Exception {
        ProjectModel model = inspect();
        InputSnapshot snapshot = InputSnapshot.capture(model, new File(temporary.getRoot(), "snapshot"));
        write("src/New.kt", "class New");
        assertFalse(snapshot.stillMatches(model));
    }
    private ProjectModel inspect() throws IOException { return new ProjectInspector().inspect(project); }
    private void write(String path, String content) throws IOException {
        File file = new File(project, path); file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
