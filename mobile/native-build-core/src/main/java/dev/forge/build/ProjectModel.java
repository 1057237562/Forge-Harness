package dev.forge.build;

import java.io.File;
import java.util.*;

/** Immutable normalized inputs; no Gradle objects, Android classes or shell command strings. */
public final class ProjectModel {
    public final File root;
    public final String name, applicationId, namespace, versionName, manifest, configuration;
    public final int compileSdk, minSdk, targetSdk, versionCode;
    public final List<String> javaRoots, resourceRoots, assetRoots, repositories, notices;
    public final List<String> kotlinRoots;
    public final List<Dependency> dependencies;

    public ProjectModel(File root, String name, String applicationId, String namespace,
                        int compileSdk, int minSdk, int targetSdk, int versionCode, String versionName,
                        String manifest, List<String> javaRoots, List<String> resourceRoots,
                        List<String> assetRoots, List<Dependency> dependencies, List<String> repositories,
                        String configuration, List<String> notices) {
        this(root, name, applicationId, namespace, compileSdk, minSdk, targetSdk, versionCode, versionName,
            manifest, javaRoots, resourceRoots, assetRoots, dependencies, repositories, configuration, notices, Collections.emptyList());
    }
    public ProjectModel(File root, String name, String applicationId, String namespace,
                        int compileSdk, int minSdk, int targetSdk, int versionCode, String versionName,
                        String manifest, List<String> javaRoots, List<String> resourceRoots,
                        List<String> assetRoots, List<Dependency> dependencies, List<String> repositories,
                        String configuration, List<String> notices, List<String> kotlinRoots) {
        this.root = root; this.name = name; this.applicationId = applicationId; this.namespace = namespace;
        this.compileSdk = compileSdk; this.minSdk = minSdk; this.targetSdk = targetSdk;
        this.versionCode = versionCode; this.versionName = versionName; this.manifest = manifest;
        this.javaRoots = immutable(javaRoots); this.resourceRoots = immutable(resourceRoots);
        this.assetRoots = immutable(assetRoots); this.dependencies = immutable(dependencies);
        this.repositories = immutable(repositories); this.configuration = configuration;
        this.notices = immutable(notices);
        this.kotlinRoots = immutable(kotlinRoots);
    }
    private static <T> List<T> immutable(List<T> items) {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }
    public enum Scope { IMPLEMENTATION, API, COMPILE_ONLY, RUNTIME_ONLY }
    public static final class Dependency {
        public final Scope scope;
        /** A fixed Maven g:a:v coordinate or a workspace-relative JAR/AAR path. */
        public final String value;
        public final boolean local;
        public final Set<String> excludes;
        public Dependency(Scope scope, String value, boolean local) {
            this(scope, value, local, Collections.emptySet());
        }
        public Dependency(Scope scope, String value, boolean local, Set<String> excludes) {
            this.scope = scope; this.value = value; this.local = local;
            this.excludes = Collections.unmodifiableSet(new LinkedHashSet<>(excludes));
        }
        @Override public String toString() { return scope + ":" + value; }
    }
}
