package dev.forge.build;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class EditorDraftStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String HASH = String.join("", java.util.Collections.nCopies(64, "a"));
    @Test public void restoresAfterStoreRecreationWithoutChangingSource() throws Exception {
        File directory = temp.newFolder("drafts");
        new EditorDraftStore(directory).update("project", "src/Main.java", HASH, "中文 draft\r\n", 1);
        EditorDraftStore.Draft draft = new EditorDraftStore(directory).load("project", "src/Main.java");
        assertEquals(HASH, draft.baseHash); assertEquals("中文 draft\r\n", draft.text);
        assertNull(new EditorDraftStore(directory).load("other-project", "src/Main.java"));
        assertNull(new EditorDraftStore(directory).load("project", "src/Other.java"));
    }
    @Test public void staleQueuedWriteCannotResurrectDiscardedDraft() throws Exception {
        EditorDraftStore store = new EditorDraftStore(temp.newFolder("drafts"));
        store.update("project", "file", HASH, "newer", 2);
        store.update("project", "file", HASH, "older", 1);
        assertEquals("newer", store.load("project", "file").text);
        store.update("project", "file", null, null, 4);
        store.update("project", "file", HASH, "late old write", 3);
        assertNull(store.load("project", "file"));
    }
    @Test public void recoveryKeepsOriginalHashSoExternalChangesCannotBeOverwritten() throws Exception {
        File root = temp.newFolder("workspace"), file = new File(root, "a.txt");
        Files.write(file.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        WorkspaceTextFile opened = WorkspaceTextFile.open(root, "a.txt");
        EditorDraftStore store = new EditorDraftStore(temp.newFolder("drafts"));
        store.update("project", "a.txt", opened.sha256, "draft changes", 1);
        Files.write(file.toPath(), "new Agent changes".getBytes(StandardCharsets.UTF_8));
        EditorDraftStore.Draft draft = store.load("project", "a.txt");
        WorkspaceTextFile recovered = WorkspaceTextFile.open(root, "a.txt").withExpectedHash(draft.baseHash);
        try { recovered.save(root, draft.text); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("changed")); }
        assertEquals("new Agent changes", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}
