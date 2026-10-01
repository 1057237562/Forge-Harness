package dev.forge.compiler;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class StageCacheTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String key(int number) { return String.format(java.util.Locale.ROOT, "%064x", number); }
    @Test public void restoresVerifiedArtifactsAndRejectsCorruption() throws Exception {
        File home = temp.newFolder("cache"), input = temp.newFolder("input"), output = temp.newFolder("output");
        Files.createDirectories(new File(input, "dev/test").toPath());
        Files.write(new File(input, "dev/test/A.class").toPath(), new byte[]{1,2,3});
        StageCache cache = new StageCache(home, new Cancellation()); cache.store(key(1), input);
        assertTrue(cache.restore(key(1), output));
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(new File(output, "dev/test/A.class").toPath()));
        Files.write(new File(home, key(1) + ".zip").toPath(), "corrupted".getBytes(StandardCharsets.UTF_8));
        File second = temp.newFolder("second"); assertFalse(cache.restore(key(1), second)); assertEquals(0, second.list().length);
    }
    @Test public void refusesToReplaceNonemptyOutput() throws Exception {
        File home = temp.newFolder("cache"), input = temp.newFolder("input"), output = temp.newFolder("output");
        Files.write(new File(input, "classes.dex").toPath(), new byte[]{1});
        Files.write(new File(output, "keep").toPath(), new byte[]{2});
        StageCache cache = new StageCache(home, new Cancellation()); cache.store(key(1), input);
        try { cache.restore(key(1), output); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("empty")); }
        assertTrue(new File(output, "keep").isFile());
    }
    @Test public void evictsLeastRecentlyUsedStageAndPreservesUnrelatedFiles() throws Exception {
        File home = temp.newFolder("cache"), input = temp.newFolder("input");
        Files.write(new File(input, "classes.dex").toPath(), new byte[]{1,2,3});
        new StageCache(home, new Cancellation()).store(key(1), input);
        long entrySize = new File(home, key(1) + ".zip").length() + 64;
        StageCache cache = new StageCache(home, new Cancellation(), entrySize * 2);
        cache.store(key(2), input);
        assertTrue(new File(home, key(2) + ".zip").setLastModified(1000));
        assertTrue(cache.restore(key(1), temp.newFolder("restored")));
        File unrelated = new File(home, "keep.txt"); Files.write(unrelated.toPath(), new byte[]{99});
        cache.store(key(3), input);
        assertTrue(new File(home, key(1) + ".zip").isFile());
        assertFalse(new File(home, key(2) + ".zip").exists());
        assertFalse(new File(home, key(2) + ".sha256").exists());
        assertTrue(new File(home, key(3) + ".zip").isFile()); assertTrue(unrelated.isFile());
    }
    @Test public void oversizedEntryIsNotCachedOrRemovedFromBuildOutput() throws Exception {
        File home = temp.newFolder("cache"), input = temp.newFolder("input");
        File generated = new File(input, "classes.dex"); Files.write(generated.toPath(), new byte[]{1,2,3});
        new StageCache(home, new Cancellation(), 1).store(key(1), input);
        assertFalse(new File(home, key(1) + ".zip").exists()); assertTrue(generated.isFile());
        assertEquals(0, home.list().length);
    }
    @Test public void removesIncompleteOwnedEntriesAndOldTemporaryFiles() throws Exception {
        File home = temp.newFolder("cache"), input = temp.newFolder("input");
        Files.write(new File(home, key(8) + ".zip").toPath(), new byte[]{1});
        Files.write(new File(home, key(9) + ".sha256").toPath(), new byte[]{2});
        File abandoned = new File(home, "stage-123.tmp"); Files.write(abandoned.toPath(), new byte[]{3}); assertTrue(abandoned.setLastModified(1000));
        File recent = new File(home, "stage-456.tmp"); Files.write(recent.toPath(), new byte[]{4});
        Files.write(new File(input, "classes.dex").toPath(), new byte[]{5});
        new StageCache(home, new Cancellation()).store(key(1), input);
        assertFalse(new File(home, key(8) + ".zip").exists());
        assertFalse(new File(home, key(9) + ".sha256").exists()); assertFalse(abandoned.exists()); assertTrue(recent.isFile());
    }
}
