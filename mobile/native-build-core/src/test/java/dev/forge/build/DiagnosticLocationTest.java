package dev.forge.build;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import static org.junit.Assert.*;

public class DiagnosticLocationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void resolvesOnlyExistingProjectSources() throws Exception {
        File project = temp.newFolder("project"), file = new File(project, "Main.java"); assertTrue(file.createNewFile());
        assertEquals("Main.java", DiagnosticLocation.resolve(project, file.getAbsolutePath(), 4).path);
        assertEquals(4, DiagnosticLocation.resolve(project, "Main.java", 4).line);
        File outside = temp.newFile("outside.java");
        try { DiagnosticLocation.resolve(project, outside.getAbsolutePath(), 1); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("outside")); }
        try { DiagnosticLocation.resolve(project, "../outside.java", 1); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("outside")); }
    }
    @Test public void refusesDeletedSourcesAndMissingLineNumbers() throws Exception {
        try { DiagnosticLocation.resolve(temp.getRoot(), "deleted.java", 2); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("no longer")); }
        try { DiagnosticLocation.resolve(temp.getRoot(), "anything", 0); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("no source")); }
    }
    @Test public void locatesUnicodeCrlfAndClampsOldLineNumbers() {
        String text = "中文\r\nsecond\nlast";
        assertEquals(0, DiagnosticLocation.lineOffset(text, 1));
        assertEquals(4, DiagnosticLocation.lineOffset(text, 2));
        assertEquals(11, DiagnosticLocation.lineOffset(text, 3));
        assertEquals(text.length(), DiagnosticLocation.lineOffset(text, 100));
    }
}
