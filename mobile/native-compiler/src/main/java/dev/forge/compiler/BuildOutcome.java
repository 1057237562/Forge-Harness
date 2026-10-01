package dev.forge.compiler;

import java.io.File;
import java.util.*;
import dev.forge.build.MavenResolver;

public final class BuildOutcome {
    public final File apk;
    public final String sha256, sourceSnapshotId;
    public final boolean cacheHit;
    public final Map<String, Long> stagesMs;
    public final MavenResolver.Resolution dependencies;
    public final Map<String, Integer> stageCacheHits;
    BuildOutcome(File apk, String sha256, String sourceSnapshotId, boolean cacheHit, Map<String, Long> stagesMs, MavenResolver.Resolution dependencies, Map<String, Integer> stageCacheHits) {
        this.apk = apk; this.sha256 = sha256; this.sourceSnapshotId = sourceSnapshotId; this.cacheHit = cacheHit;
        this.stagesMs = Collections.unmodifiableMap(new LinkedHashMap<>(stagesMs));
        this.dependencies = dependencies;
        this.stageCacheHits = Collections.unmodifiableMap(new LinkedHashMap<>(stageCacheHits));
    }
}
