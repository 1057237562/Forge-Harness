package dev.forge.build;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class WorkspaceTextFileTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void preservesBomUnicodeAndLineEndings() throws Exception {
        File file = temp.newFile("Source.java");
        Files.write(file.toPath(), "\ufeff你好\r\nnext\r\n".getBytes(StandardCharsets.UTF_8));
        WorkspaceTextFile doc = WorkspaceTextFile.open(temp.getRoot(), "Source.java");
        assertEquals("你好\r\nnext\r\n", doc.text); assertTrue(doc.bom);
        WorkspaceTextFile saved = doc.save(temp.getRoot(), doc.text + "last\r\n");
        assertEquals("\ufeff你好\r\nnext\r\nlast\r\n", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertEquals(saved.sha256, WorkspaceFiles.sha256(file));
    }
    @Test public void refusesToOverwriteExternalChanges() throws Exception {
        File file = temp.newFile("a.txt"); Files.write(file.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        WorkspaceTextFile doc = WorkspaceTextFile.open(temp.getRoot(), "a.txt");
        Files.write(file.toPath(), "Agent changed this".getBytes(StandardCharsets.UTF_8));
        try { doc.save(temp.getRoot(), "editor change"); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("changed")); }
        assertEquals("Agent changed this", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void refusesInvalidUtf8AndBinary() throws Exception {
        File file = temp.newFile("data");
        for (byte[] bytes : new byte[][]{ {(byte) 0xc3, 0x28}, {0, 1, 2} }) {
            Files.write(file.toPath(), bytes);
            try { WorkspaceTextFile.open(temp.getRoot(), "data"); fail(); } catch (IOException expected) { }
        }
    }
    @Test public void doesNotTurnOversizedFilesIntoTruncatedDocuments() throws Exception {
        File file = temp.newFile("large"); Files.write(file.toPath(), new byte[WorkspaceTextFile.MAX_BYTES + 1]);
        try { WorkspaceTextFile.open(temp.getRoot(), "large"); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("limit")); }
    }
    @Test public void refusesPathTraversal() throws Exception {
        try { WorkspaceTextFile.open(temp.getRoot(), "../outside"); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("escapes")); }
    }
}
