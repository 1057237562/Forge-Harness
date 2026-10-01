package dev.forge.build;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ArchivePathsTest {
    @Test public void acceptsNestedProjectsIncludingForgeConfiguration() throws Exception {
        ArchivePaths paths = new ArchivePaths();
        assertEquals("project/.forge/project.json", paths.accept("project/.forge/project.json", false));
        assertEquals("project", paths.accept("project/", true));
        assertEquals("project/src/Main.java", paths.accept("project/src/Main.java", false));
    }
    @Test public void rejectsTraversalAbsoluteAndAmbiguousNames() throws Exception {
        for (String name : new String[]{"../outside", "/absolute", "C:/drive", "a\\b", "a//b", "a/./b", "a/../b", "bad\0name"}) {
            try { new ArchivePaths().accept(name, false); fail(name); } catch (IOException expected) { }
        }
    }
    @Test public void rejectsDuplicateFilesAndCaseAliases() throws Exception {
        ArchivePaths paths = new ArchivePaths(); paths.accept("src/Main.java", false);
        for (String name : new String[]{"src/Main.java", "src/main.java", "Src/Other.java"}) {
            try { paths.accept(name, false); fail(name); } catch (IOException expected) { }
        }
    }
    @Test public void rejectsFileDirectoryCollisionsInEitherOrder() throws Exception {
        ArchivePaths first = new ArchivePaths(); first.accept("src", false);
        try { first.accept("src/Main.java", false); fail(); } catch (IOException expected) { }
        ArchivePaths second = new ArchivePaths(); second.accept("src/Main.java", false);
        try { second.accept("src", false); fail(); } catch (IOException expected) { }
    }
}
