package dev.forge.build;
import java.io.*;

/** Resolves exactly one journal's output and verifies bytes before installation/export. */
public final class VerifiedBuildArtifact {
    private VerifiedBuildArtifact() { }
    public static File resolve(File builds, String buildId, String path, String expectedSha256) throws IOException {
        if (buildId == null || !buildId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IOException("Invalid build identity");
        if (expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")) throw new IOException("Invalid artifact checksum");
        File expected = WorkspaceFiles.resolve(builds, buildId + "/output/app.apk");
        if (path == null || !new File(path).getCanonicalFile().equals(expected) || !expected.isFile() || expected.length() == 0)
            throw new IOException("Artifact does not belong to this build");
        if (!WorkspaceFiles.sha256(expected).equals(expectedSha256)) throw new IOException("APK changed since the build completed; rebuild before installing");
        return expected;
    }
}
