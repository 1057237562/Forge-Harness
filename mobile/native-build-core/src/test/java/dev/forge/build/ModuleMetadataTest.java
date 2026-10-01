package dev.forge.build;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class ModuleMetadataTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Map<String, byte[]> remote = new HashMap<>();
    private static final String BASE = "https://repo.example.test/";
    private File cacheHome;
    private int requests;
    @Before public void setup() throws Exception { cacheHome = temporary.newFolder("cache"); }
    private JSONObject dependency(String artifact, String version) {
        return new JSONObject().put("group", "g").put("module", artifact).put("version", new JSONObject().put("requires", version));
    }
    private JSONObject variant(String name, String usage, String file, JSONObject... dependencies) {
        JSONObject result = new JSONObject().put("name", name).put("attributes", new JSONObject()
            .put("org.gradle.category", "library").put("org.gradle.usage", usage).put("org.gradle.jvm.version", 8).put("org.gradle.libraryelements", "jar"));
        result.put("dependencies", new JSONArray(Arrays.asList(dependencies)));
        if (file != null) result.put("files", new JSONArray().put(new JSONObject().put("name", file).put("url", file)));
        return result;
    }
    private void publish(String artifact, String version, JSONObject... variants) throws Exception {
        MavenCoordinate c = MavenCoordinate.parse("g:" + artifact + ":" + version);
        String marker = variants.length == 0 ? "" : "<!-- do_not_remove: published-with-gradle-metadata -->";
        remote.put(BASE + c.path("pom"), ("<project>" + marker + "<groupId>g</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version></project>").getBytes(StandardCharsets.UTF_8));
        if (variants.length == 0) remote.put(BASE + c.path("jar"), ("jar:" + c).getBytes(StandardCharsets.UTF_8));
        else {
            JSONObject module = new JSONObject().put("formatVersion", "1.1").put("component", new JSONObject().put("group", "g").put("module", artifact).put("version", version))
                .put("variants", new JSONArray(Arrays.asList(variants)));
            remote.put(BASE + c.path("module"), module.toString().getBytes(StandardCharsets.UTF_8));
            String parent = BASE + c.path("module").substring(0, c.path("module").lastIndexOf('/') + 1);
            for (JSONObject variant : variants) {
                JSONArray files = variant.optJSONArray("files");
                if (files != null) for (int i = 0; i < files.length(); i++) {
                    String file = files.getJSONObject(i).getString("url"); remote.put(parent + file, ("artifact:" + file).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }
    private MavenResolver.Resolution resolve(boolean offline, ProjectModel.Dependency... dependencies) throws Exception {
        RepositoryCache cache = new RepositoryCache(cacheHome, (uri, out, limit, check) -> {
            requests++; byte[] data = remote.get(uri.toString());
            if (data == null) return false; Files.write(out.toPath(), data); return true;
        }, offline, () -> {}, text -> {});
        ProjectModel project = new ProjectModel(temporary.getRoot(), "test", "dev.forge.test", "dev.forge.test", 29, 28, 29, 1, "1",
            "manifest.xml", Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Arrays.asList(dependencies),
            Collections.singletonList(BASE), "test", Collections.emptyList());
        return new MavenResolver(cache, project.repositories, () -> {}).resolve(project);
    }
    private ProjectModel.Dependency dep(String id) { return new ProjectModel.Dependency(ProjectModel.Scope.IMPLEMENTATION, id, false); }
    private MavenResolver.Artifact artifact(MavenResolver.Resolution resolution, String id) {
        return resolution.artifacts.stream().filter(a -> a.id.equals(id)).findFirst().orElseThrow(() -> new AssertionError("Missing " + id));
    }
    @Test public void acceptsMultiplatformOwnerAndArtifactAlias() throws Exception {
        JSONObject api = variant("api", "java-api", "a-jvm-1.jar");
        api.getJSONArray("files").getJSONObject(0).put("url", "a-1.jar");
        publish("a", "1", api);
        String path = BASE + MavenCoordinate.parse("g:a:1").path("module");
        JSONObject module = new JSONObject(new String(remote.get(path), StandardCharsets.UTF_8));
        module.put("component", new JSONObject().put("group", "g").put("module", "parent").put("version", "1")
            .put("url", "../../parent/1/parent-1.module"));
        remote.put(path, module.toString().getBytes(StandardCharsets.UTF_8));
        MavenResolver.Resolution result = resolve(false, new ProjectModel.Dependency(ProjectModel.Scope.COMPILE_ONLY, "g:a:1", false));
        assertTrue(artifact(result, "g:a:1@a-jvm-1.jar").compile);
        assertEquals(result.fingerprint, resolve(true, new ProjectModel.Dependency(ProjectModel.Scope.COMPILE_ONLY, "g:a:1", false)).fingerprint);
    }
    @Test public void rejectsForeignComponentOwner() throws Exception {
        publish("a", "1", variant("api", "java-api", "a-1.jar"));
        String path = BASE + MavenCoordinate.parse("g:a:1").path("module");
        JSONObject module = new JSONObject(new String(remote.get(path), StandardCharsets.UTF_8));
        module.getJSONObject("component").put("url", "https://evil.test/g/a/1/a-1.module");
        remote.put(path, module.toString().getBytes(StandardCharsets.UTF_8));
        try { resolve(false, dep("g:a:1")); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("Component owner URL")); }
    }
    @Test public void usesModuleOnlyDependenciesWithSeparateApiRuntimeScopes() throws Exception {
        publish("a", "1", variant("api", "java-api", "a-1.jar", dependency("api", "1")),
            variant("runtime", "java-runtime", "a-1.jar", dependency("runtime", "1")));
        publish("api", "1"); publish("runtime", "1");
        MavenResolver.Resolution result = resolve(false, dep("g:a:1"));
        assertTrue(artifact(result, "g:api:1").compile); assertFalse(artifact(result, "g:api:1").runtime);
        assertFalse(artifact(result, "g:runtime:1").compile); assertTrue(artifact(result, "g:runtime:1").runtime);
        assertTrue(artifact(result, "g:a:1@a-1.jar").compile); assertTrue(artifact(result, "g:a:1@a-1.jar").runtime);
        assertTrue(result.metadata.containsKey("module:g:a:1"));
    }
    @Test public void supportsDistinctApiAndRuntimeFiles() throws Exception {
        publish("a", "1", variant("api", "java-api", "a-api.jar"), variant("runtime", "java-runtime", "a-runtime.jar"));
        MavenResolver.Resolution result = resolve(false, dep("g:a:1"));
        assertTrue(artifact(result, "g:a:1@a-api.jar").compile); assertFalse(artifact(result, "g:a:1@a-api.jar").runtime);
        assertFalse(artifact(result, "g:a:1@a-runtime.jar").compile); assertTrue(artifact(result, "g:a:1@a-runtime.jar").runtime);
    }
    @Test public void prefersAndroidVariantAndIgnoresFeatureCapabilities() throws Exception {
        JSONObject android = variant("android", "java-runtime", "a-android.jar");
        android.getJSONObject("attributes").put("org.jetbrains.kotlin.platform.type", "androidJvm");
        JSONObject feature = variant("feature", "java-runtime", "a-feature.jar");
        feature.put("capabilities", new JSONArray().put(new JSONObject().put("group", "g").put("name", "feature").put("version", "1")));
        publish("a", "1", variant("jvm", "java-runtime", "a-jvm.jar"), android, feature);
        MavenResolver.Resolution result = resolve(false, new ProjectModel.Dependency(ProjectModel.Scope.RUNTIME_ONLY, "g:a:1", false));
        assertEquals(1, result.artifacts.size()); assertEquals("g:a:1@a-android.jar", result.artifacts.get(0).id);
    }
    @Test public void followsAvailableAtForEachUsage() throws Exception {
        JSONObject api = variant("api", "java-api", null), runtime = variant("runtime", "java-runtime", null);
        JSONObject redirect = new JSONObject().put("group", "g").put("module", "target").put("version", "1").put("url", "../../target/1/target-1.module");
        api.put("available-at", redirect); runtime.put("available-at", redirect);
        publish("a", "1", api, runtime);
        publish("target", "1", variant("api", "java-api", "target-1.jar"), variant("runtime", "java-runtime", "target-1.jar"));
        MavenResolver.Resolution result = resolve(false, dep("g:a:1"));
        assertEquals(1, result.artifacts.size()); assertEquals("g:target:1@target-1.jar", result.artifacts.get(0).id);
    }
    @Test public void rejectsForeignRedirectAndUnsafeFileUrls() throws Exception {
        JSONObject runtime = variant("runtime", "java-runtime", null).put("available-at", new JSONObject().put("group", "g").put("module", "target").put("version", "1").put("url", "https://evil.test/target-1.module"));
        publish("a", "1", runtime);
        try { resolve(false, new ProjectModel.Dependency(ProjectModel.Scope.RUNTIME_ONLY, "g:a:1", false)); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("does not match")); }
        JSONObject broken = variant("api", "java-api", "../escape.jar"); publish("b", "1", broken);
        try { resolve(false, dep("g:b:1")); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("same-directory")); }
    }
    @Test public void verifiesPublishedChecksum() throws Exception {
        JSONObject api = variant("api", "java-api", "a-1.jar");
        api.getJSONArray("files").getJSONObject(0).put("sha256", String.join("", Collections.nCopies(64, "0")));
        publish("a", "1", api);
        try { resolve(false, dep("g:a:1")); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("checksum/size mismatch")); }
    }
    @Test public void strictConstraintCannotBeOverriddenByAnIncompatiblePin() throws Exception {
        JSONObject dependency = dependency("b", "1"); dependency.getJSONObject("version").put("strictly", "1");
        publish("a", "1", variant("api", "java-api", "a-1.jar", dependency), variant("runtime", "java-runtime", "a-1.jar", dependency));
        publish("b", "2");
        try { resolve(false, dep("g:a:1"), dep("g:b:2")); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("constraint rejects")); }
    }
    @Test public void acceptsNewerNumericVersionForMinimumConstraint() throws Exception {
        JSONObject api = variant("api", "java-api", "a-1.jar", dependency("b", "3.0"));
        api.put("dependencyConstraints", new JSONArray().put(dependency("b", "2.0")));
        publish("a", "1", api, variant("runtime", "java-runtime", "a-1.jar")); publish("b", "3.0");
        assertNotNull(artifact(resolve(false, dep("g:a:1")), "g:b:3.0"));
    }
    @Test public void metadataAndRedirectsWorkOfflineAfterCaching() throws Exception {
        publish("a", "1", variant("api", "java-api", "a-1.jar"), variant("runtime", "java-runtime", "a-1.jar"));
        MavenResolver.Resolution first = resolve(false, dep("g:a:1")); int before = requests;
        remote.clear(); assertEquals(first.fingerprint, resolve(true, dep("g:a:1")).fingerprint); assertEquals(before, requests);
    }
    @Test public void compileOnlyDoesNotDemandUnusedRuntimeVariant() throws Exception {
        publish("a", "1", variant("api", "java-api", "a-1.jar"));
        MavenResolver.Resolution result = resolve(false, new ProjectModel.Dependency(ProjectModel.Scope.COMPILE_ONLY, "g:a:1", false));
        assertTrue(result.artifacts.get(0).compile); assertFalse(result.artifacts.get(0).runtime);
    }
    @Test public void refusesAmbiguityInsteadOfPickingFirstVariant() throws Exception {
        publish("a", "1", variant("apiOne", "java-api", "a-1.jar"), variant("apiTwo", "java-api", "a-other.jar"));
        try { resolve(false, dep("g:a:1")); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("Ambiguous")); }
    }
    @Test public void rejectsIncompatibleJvmTarget() throws Exception {
        JSONObject api = variant("api", "java-api", "a-1.jar"); api.getJSONObject("attributes").put("org.gradle.jvm.version", 17);
        publish("a", "1", api);
        try { resolve(false, dep("g:a:1")); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("No compatible")); }
    }
}
