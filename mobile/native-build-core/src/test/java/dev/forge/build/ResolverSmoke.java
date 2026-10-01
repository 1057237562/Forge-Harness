package dev.forge.build;

import java.io.File;
import java.util.*;

/** Explicit online smoke test, separate from hermetic unit tests. */
public final class ResolverSmoke {
    public static void main(String[] args) throws Exception {
        File cacheHome = new File(args[0]);
        String coordinate = args[1];
        List<String> repositories = Collections.singletonList("https://repo.maven.apache.org/maven2/");
        ProjectModel project = new ProjectModel(new File("."), "smoke", "dev.forge.smoke", "dev.forge.smoke", 29, 28, 29,
            1, "1", "AndroidManifest.xml", Collections.singletonList("src"), Collections.emptyList(), Collections.emptyList(),
            Collections.singletonList(new ProjectModel.Dependency(ProjectModel.Scope.IMPLEMENTATION, coordinate, false)),
            repositories, "smoke", Collections.emptyList());
        for (boolean offline : new boolean[]{false, true}) {
            RepositoryCache cache = new RepositoryCache(cacheHome, new RepositoryCache.HttpsTransport(), offline, () -> {}, System.out::println);
            MavenResolver.Resolution result = new MavenResolver(cache, repositories, () -> {}).resolve(project);
            System.out.println("offline=" + offline + " fingerprint=" + result.fingerprint);
            for (MavenResolver.Artifact artifact : result.artifacts)
                System.out.println(artifact.id + " " + artifact.type + " " + artifact.sha256 + " " + artifact.file);
        }
    }
}
