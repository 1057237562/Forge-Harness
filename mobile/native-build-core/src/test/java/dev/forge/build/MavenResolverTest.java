package dev.forge.build;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class MavenResolverTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Map<String, byte[]> remote = new HashMap<>();
    private File cacheDirectory, workspace;
    private int downloads;
    private static final String BASE = "https://repo.example.test/maven/";
    private final RepositoryCache.Transport transport = (uri, target, limit, cancel) -> {
        cancel.check(); downloads++;
        byte[] bytes = remote.get(uri.toString());
        if (bytes == null) return false;
        Files.write(target.toPath(), bytes); return true;
    };
    @Before public void setup() throws Exception { cacheDirectory = temporary.newFolder("cache"); workspace = temporary.newFolder("project"); }
    private ProjectModel project(ProjectModel.Dependency... dependencies) {
        return new ProjectModel(workspace, "test", "dev.forge.test", "dev.forge.test", 29, 28, 29, 1, "1", "AndroidManifest.xml",
            Collections.singletonList("src"), Collections.singletonList("res"), Collections.emptyList(), Arrays.asList(dependencies),
            Collections.singletonList(BASE), ".forge/project.json", Collections.emptyList());
    }
    private ProjectModel.Dependency dep(String value) { return new ProjectModel.Dependency(ProjectModel.Scope.IMPLEMENTATION, value, false); }
    private MavenResolver.Resolution resolve(boolean offline, ProjectModel.Dependency... deps) throws Exception {
        RepositoryCache cache = new RepositoryCache(cacheDirectory, transport, offline, () -> {}, text -> {});
        return new MavenResolver(cache, Collections.singletonList(BASE), () -> {}).resolve(project(deps));
    }
    private void publish(String id, String body) throws Exception { publish(id, body, "jar"); }
    private void publish(String id, String body, String type) throws Exception {
        MavenCoordinate c = MavenCoordinate.parse(id);
        String xml = "<project><modelVersion>4.0.0</modelVersion><groupId>" + c.group + "</groupId><artifactId>" + c.artifact + "</artifactId><version>" + c.version + "</version><packaging>" + type + "</packaging>" + body + "</project>";
        remote.put(BASE + c.path("pom"), xml.getBytes(StandardCharsets.UTF_8));
        if (!type.equals("pom")) remote.put(BASE + c.path(type), ("artifact:" + id).getBytes(StandardCharsets.UTF_8));
    }
    private String d(String artifact, String version, String extra) {
        return "<dependency><groupId>g</groupId><artifactId>" + artifact + "</artifactId>" +
            (version.isEmpty() ? "" : "<version>" + version + "</version>") + extra + "</dependency>";
    }
    private MavenResolver.Artifact find(MavenResolver.Resolution result, String id) {
        return result.artifacts.stream().filter(a -> a.id.equals(id)).findFirst().orElseThrow(() -> new AssertionError("Missing " + id));
    }
    @Test public void resolvesTransitivesAndExcludesNonRuntimeScopes() throws Exception {
        publish("g:app:1", "<dependencies>" + d("compile", "1", "") + d("runtime", "1", "<scope>runtime</scope>") +
            d("optional", "1", "<optional>true</optional>") + d("provided", "1", "<scope>provided</scope>") + d("test", "1", "<scope>test</scope>") + "</dependencies>");
        publish("g:compile:1", ""); publish("g:runtime:1", "");
        MavenResolver.Resolution result = resolve(false, dep("g:app:1"));
        assertEquals(3, result.artifacts.size());
        assertTrue(find(result, "g:compile:1").compile);
        assertFalse(find(result, "g:runtime:1").compile);
        assertTrue(find(result, "g:runtime:1").runtime);
        assertEquals("g:app:1", result.artifacts.get(result.artifacts.size() - 1).id);
    }
    @Test public void compileOnlyDoesNotLeakTransitiveRuntimeArtifacts() throws Exception {
        publish("g:app:1", "<dependencies>" + d("child", "1", "<scope>runtime</scope>") + "</dependencies>"); publish("g:child:1", "");
        MavenResolver.Resolution result = resolve(false, new ProjectModel.Dependency(ProjectModel.Scope.COMPILE_ONLY, "g:app:1", false));
        assertTrue(find(result, "g:child:1").compile);
        for (MavenResolver.Artifact a : result.artifacts) assertFalse(a.runtime);
    }
    @Test public void mergesScopesReachedThroughDifferentRoots() throws Exception {
        publish("g:app:1", "<dependencies>" + d("child", "1", "") + "</dependencies>"); publish("g:child:1", "");
        MavenResolver.Resolution result = resolve(false, new ProjectModel.Dependency(ProjectModel.Scope.COMPILE_ONLY, "g:app:1", false),
            new ProjectModel.Dependency(ProjectModel.Scope.RUNTIME_ONLY, "g:child:1", false));
        assertTrue(find(result, "g:child:1").compile); assertTrue(find(result, "g:child:1").runtime);
    }
    @Test public void excludedPathDoesNotSuppressAnIndependentPath() throws Exception {
        String exclusion = "<exclusions><exclusion><groupId>g</groupId><artifactId>leaf</artifactId></exclusion></exclusions>";
        publish("g:a:1", "<dependencies>" + d("middle", "1", exclusion) + "</dependencies>");
        publish("g:b:1", "<dependencies>" + d("middle", "1", "") + "</dependencies>");
        publish("g:middle:1", "<dependencies>" + d("leaf", "1", "") + "</dependencies>"); publish("g:leaf:1", "");
        assertEquals(2, resolve(false, dep("g:a:1")).artifacts.size());
        assertNotNull(find(resolve(false, dep("g:a:1"), dep("g:b:1")), "g:leaf:1"));
    }
    @Test public void supportsDirectWildcardExclusions() throws Exception {
        publish("g:a:1", "<dependencies>" + d("leaf", "1", "") + "</dependencies>");
        assertEquals(1, resolve(false, new ProjectModel.Dependency(ProjectModel.Scope.IMPLEMENTATION, "g:a:1", false, Collections.singleton("g:*"))).artifacts.size());
    }
    @Test public void conflictsRequireAnExplicitRootPin() throws Exception {
        publish("g:a:1", "<dependencies>" + d("common", "1", "") + "</dependencies>");
        publish("g:b:1", "<dependencies>" + d("common", "2", "") + "</dependencies>"); publish("g:common:1", ""); publish("g:common:2", "");
        try { resolve(false, dep("g:a:1"), dep("g:b:1")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("DEPENDENCY_VERSION_CONFLICT")); }
        MavenResolver.Resolution result = resolve(false, dep("g:a:1"), dep("g:b:1"), dep("g:common:2"));
        assertNotNull(find(result, "g:common:2")); assertEquals(3, result.artifacts.size()); assertFalse(result.notices.isEmpty());
    }
    @Test public void inheritsParentManagementAndChildPropertyOverrides() throws Exception {
        publish("g:parent:1", "<properties><library.version>1</library.version></properties><dependencyManagement><dependencies>" + d("leaf", "${library.version}", "") + "</dependencies></dependencyManagement>", "pom");
        publish("g:app:1", "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent><properties><library.version>2</library.version></properties><dependencies>" + d("leaf", "", "") + "</dependencies>");
        publish("g:leaf:2", "");
        assertNotNull(find(resolve(false, dep("g:app:1")), "g:leaf:2"));
    }
    @Test public void importsBomAndLocalManagementWins() throws Exception {
        publish("g:bom:1", "<dependencyManagement><dependencies>" + d("leaf", "1", "") + "</dependencies></dependencyManagement>", "pom");
        publish("g:app:1", "<dependencyManagement><dependencies>" + d("bom", "1", "<scope>import</scope><type>pom</type>") +
            d("leaf", "2", "") + "</dependencies></dependencyManagement><dependencies>" + d("leaf", "", "") + "</dependencies>");
        publish("g:leaf:2", ""); assertNotNull(find(resolve(false, dep("g:app:1")), "g:leaf:2"));
    }
    @Test public void cachedResolutionNeedsNoNetworkAndKeepsFingerprint() throws Exception {
        publish("g:a:1", ""); MavenResolver.Resolution first = resolve(false, dep("g:a:1"));
        int before = downloads; remote.clear(); MavenResolver.Resolution second = resolve(true, dep("g:a:1"));
        assertEquals(before, downloads); assertEquals(first.fingerprint, second.fingerprint);
    }
    @Test public void detectsCorruptionAndRepairsItOnline() throws Exception {
        publish("g:a:1", ""); MavenResolver.Resolution first = resolve(false, dep("g:a:1"));
        Files.write(first.artifacts.get(0).file.toPath(), "corrupted".getBytes(StandardCharsets.UTF_8));
        try { resolve(true, dep("g:a:1")); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("OFFLINE_CACHE_MISS")); }
        assertEquals(first.fingerprint, resolve(false, dep("g:a:1")).fingerprint);
    }
    @Test public void missingOfflineDependencyIsExplicit() throws Exception {
        try { resolve(true, dep("g:missing:1")); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("OFFLINE_CACHE_MISS")); }
    }
    @Test public void detectsDependencyAndParentCycles() throws Exception {
        publish("g:a:1", "<dependencies>" + d("b", "1", "") + "</dependencies>");
        publish("g:b:1", "<dependencies>" + d("a", "1", "") + "</dependencies>");
        try { resolve(false, dep("g:a:1")); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("Cyclic dependency")); }
        publish("g:p:1", "<parent><groupId>g</groupId><artifactId>p</artifactId><version>1</version></parent>", "pom");
        try { resolve(false, dep("g:p:1")); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("Cyclic POM")); }
    }
    @Test public void rejectsMaliciousPomAndRepositoryPaths() throws Exception {
        publish("g:a:1", ""); remote.put(BASE + "g/a/1/a-1.pom", "<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><project/>".getBytes(StandardCharsets.UTF_8));
        try { resolve(false, dep("g:a:1")); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("DTD")); }
        for (String id : Arrays.asList("..:a:1", "g:a:..", "g:a:1.+", "g:a:[1,2)")) {
            try { MavenCoordinate.parse(id); fail(id); } catch (IOException expected) { }
        }
        for (String url : Arrays.asList("http://example.test/", "https://user:pass@example.test/", "https://example.test/?key=secret")) {
            try { RepositoryCache.normalized(url); fail(url); } catch (IOException expected) { }
        }
    }
    @Test public void picksAarPackagingWithoutAssumingJar() throws Exception {
        publish("g:android:1", "", "aar");
        MavenResolver.Artifact artifact = find(resolve(false, dep("g:android:1")), "g:android:1");
        assertEquals("aar", artifact.type); assertTrue(artifact.file.getName().endsWith(".aar"));
    }
    @Test public void cancellationStopsBeforeDownloading() throws Exception {
        RepositoryCache cache = new RepositoryCache(cacheDirectory, transport, false, () -> { throw new InterruptedIOException("cancelled"); }, text -> {});
        try { cache.get("g/a/1/a-1.jar", Collections.singletonList(BASE), 1024); fail(); }
        catch (InterruptedIOException expected) { assertEquals(0, downloads); }
    }
    @Test public void doesNotSilentlyDropGradleModuleOnlyDependencies() throws Exception {
        publish("g:a:1", "<!-- do_not_remove: published-with-gradle-metadata -->");
        try { resolve(false, dep("g:a:1")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains(".module")); }
    }
    @Test public void reusableResolverDoesNotLeakPreviousGraphMetadata() throws Exception {
        publish("g:a:1", ""); publish("g:b:1", "");
        RepositoryCache cache = new RepositoryCache(cacheDirectory, transport, false, () -> {}, text -> {});
        MavenResolver resolver = new MavenResolver(cache, Collections.singletonList(BASE), () -> {});
        resolver.resolve(project(dep("g:a:1")));
        assertEquals(resolve(false, dep("g:b:1")).fingerprint, resolver.resolve(project(dep("g:b:1"))).fingerprint);
    }
    @Test public void rejectsAmbiguousDuplicatePomDependencies() throws Exception {
        publish("g:a:1", "<dependencies>" + d("b", "1", "") + d("b", "2", "") + "</dependencies>");
        try { resolve(false, dep("g:a:1")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Duplicate POM dependency")); }
    }
}
