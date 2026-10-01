package dev.forge.build;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.Assert.*;

public class VerifiedBuildArtifactTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void checksBothBuildIdentityAndContent() throws Exception {
        String id = UUID.randomUUID().toString();
        File apk = new File(temp.getRoot(), id + "/output/app.apk"); assertTrue(apk.getParentFile().mkdirs());
        Files.write(apk.toPath(), new byte[]{1,2,3});
        String hash = WorkspaceFiles.sha256(apk);
        assertEquals(apk.getCanonicalFile(), VerifiedBuildArtifact.resolve(temp.getRoot(), id, apk.getPath(), hash));
        try { VerifiedBuildArtifact.resolve(temp.getRoot(), UUID.randomUUID().toString(), apk.getPath(), hash); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("belong")); }
        Files.write(apk.toPath(), new byte[]{4,5,6});
        try { VerifiedBuildArtifact.resolve(temp.getRoot(), id, apk.getPath(), hash); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("changed")); }
    }
    @Test public void rejectsTraversalAndMissingHash() throws Exception {
        try { VerifiedBuildArtifact.resolve(temp.getRoot(), "../../other", "outside.apk", ""); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("identity")); }
        try { VerifiedBuildArtifact.resolve(temp.getRoot(), UUID.randomUUID().toString(), "outside.apk", null); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("checksum")); }
    }
}
