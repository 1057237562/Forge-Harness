package dev.forge.build;

import java.io.*;
import java.net.URI;
import java.util.*;
import org.json.*;

/** Reads published metadata only. No Gradle invocation, plugin loading, or script evaluation. */
final class GradleModuleMetadata {
    static final class FileEntry {
        final String name, relativePath, sha256, sha512;
        final long size;
        FileEntry(String name, String path, String sha256, String sha512, long size) {
            this.name = name; relativePath = path; this.sha256 = sha256; this.sha512 = sha512; this.size = size;
        }
        String type() { return name.endsWith(".aar") ? "aar" : "jar"; }
    }
    static final class Dependency {
        final MavenCoordinate coordinate;
        final Set<String> excludes;
        Dependency(MavenCoordinate coordinate, Set<String> excludes) { this.coordinate = coordinate; this.excludes = excludes; }
    }
    static final class Constraint {
        final String ga, required, strict, preferred;
        final Set<String> rejected;
        Constraint(String ga, String required, String strict, String preferred, Set<String> rejected) {
            this.ga = ga; this.required = required; this.strict = strict; this.preferred = preferred; this.rejected = rejected;
        }
    }
    static final class Variant {
        final String name;
        final MavenCoordinate redirect;
        final List<FileEntry> files;
        final List<Dependency> dependencies;
        final List<Constraint> constraints;
        Variant(String name, MavenCoordinate redirect, List<FileEntry> files, List<Dependency> dependencies, List<Constraint> constraints) {
            this.name = name; this.redirect = redirect; this.files = files; this.dependencies = dependencies; this.constraints = constraints;
        }
    }
    private final JSONObject json;
    private final MavenCoordinate coordinate;
    private final MavenCoordinate owner;
    private final String repository;
    GradleModuleMetadata(File file, MavenCoordinate coordinate, String repository) throws IOException {
        this.coordinate = coordinate; this.repository = repository;
        try {
            json = new JSONObject(WorkspaceFiles.readUtf8(file, 2 * 1024 * 1024));
            if (!Arrays.asList("1.0", "1.1").contains(json.getString("formatVersion"))) throw fail("Unsupported metadata format");
            JSONObject component = json.getJSONObject("component");
            owner = new MavenCoordinate(component.getString("group"), component.getString("module"), component.getString("version"), "");
            if (component.has("url")) {
                URI declared = URI.create(repository + coordinate.path("module")).resolve(component.getString("url")).normalize();
                if (!declared.equals(URI.create(repository + owner.path("module")))) throw fail("Component owner URL does not match its repository coordinate");
            } else if (!owner.equals(coordinate)) throw fail("Metadata component does not match its coordinate");
        } catch (JSONException error) { throw fail("Invalid module metadata: " + error.getMessage()); }
    }
    Variant select(boolean runtime) throws IOException {
        try {
            JSONArray variants = json.getJSONArray("variants");
            JSONObject best = null; int score = -1; boolean ambiguous = false;
            String usage = runtime ? "java-runtime" : "java-api";
            for (int i = 0; i < variants.length(); i++) {
                JSONObject candidate = variants.getJSONObject(i);
                JSONObject attrs = candidate.optJSONObject("attributes");
                if (attrs == null || !attrs.optString("org.gradle.usage").equals(usage) ||
                    !attrs.optString("org.gradle.category", "library").equals("library")) continue;
                String platform = attrs.optString("org.jetbrains.kotlin.platform.type", "jvm");
                if (!Arrays.asList("jvm", "androidJvm").contains(platform)) continue;
                String elements = attrs.optString("org.gradle.libraryelements", "jar");
                if (!Arrays.asList("jar", "aar").contains(elements)) continue;
                if (!attrs.optString("org.gradle.dependency.bundling", "external").equals("external")) continue;
                if (attrs.optInt("org.gradle.jvm.version", 8) > 8) continue;
                if (!defaultCapability(candidate.optJSONArray("capabilities"))) continue;
                int rank = (platform.equals("androidJvm") ? 8 : 0) +
                    (attrs.optString("org.gradle.jvm.environment").equals("android") ? 4 : 0) +
                    (attrs.optString("com.android.build.api.attributes.BuildTypeAttr", "release").equals("release") ? 2 : 0);
                if (rank > score) { score = rank; best = candidate; ambiguous = false; }
                else if (rank == score) ambiguous = true;
            }
            if (best == null) throw fail("No compatible Android/JVM Java 8 " + usage + " variant");
            if (ambiguous) throw fail("Ambiguous " + usage + " variants; explicit variant support is required");
            validateAttributes(best.getJSONObject("attributes"));
            String name = best.getString("name");
            List<FileEntry> files = new ArrayList<>();
            List<Dependency> dependencies = new ArrayList<>();
            List<Constraint> constraints = new ArrayList<>();
            MavenCoordinate redirect = null;
            if (best.has("available-at")) {
                JSONObject available = best.getJSONObject("available-at");
                redirect = new MavenCoordinate(available.getString("group"), available.getString("module"), available.getString("version"), "");
                URI expected = URI.create(repository + redirect.path("module"));
                URI actual = URI.create(repository + coordinate.path("module")).resolve(available.getString("url")).normalize();
                if (!expected.equals(actual)) throw fail("available-at URL does not match its repository coordinate");
                if (nonempty(best, "files") || nonempty(best, "dependencies") || nonempty(best, "dependencyConstraints"))
                    throw fail("Redirecting variant also declares files or dependencies");
            }
            JSONArray payload = best.optJSONArray("files");
            if (payload != null) for (int i = 0; i < payload.length(); i++) {
                JSONObject item = payload.getJSONObject(i);
                String fileName = item.getString("name"), url = item.getString("url");
                if (!fileName.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.(jar|aar)") || !url.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.(jar|aar)") ||
                    !fileName.substring(fileName.lastIndexOf('.')).equals(url.substring(url.lastIndexOf('.'))))
                    throw fail("Only same-directory JAR/AAR files are accepted: " + fileName);
                String parent = coordinate.path("module").substring(0, coordinate.path("module").lastIndexOf('/') + 1);
                String sha256 = item.optString("sha256", ""), sha512 = item.optString("sha512", "");
                if ((!sha256.isEmpty() && !sha256.matches("[a-fA-F0-9]{64}")) || (!sha512.isEmpty() && !sha512.matches("[a-fA-F0-9]{128}")))
                    throw fail("Invalid published artifact checksum");
                long size = item.optLong("size", -1);
                if (size == 0 || size > 128L * 1024 * 1024 || size < -1) throw fail("Invalid published artifact size");
                files.add(new FileEntry(fileName, parent + url, sha256.toLowerCase(Locale.ROOT), sha512.toLowerCase(Locale.ROOT), size));
            }
            JSONArray requested = best.optJSONArray("dependencies");
            if (requested != null) for (int i = 0; i < requested.length(); i++) {
                JSONObject dep = requested.getJSONObject(i);
                if (nonempty(dep, "requestedCapabilities") || dep.optBoolean("endorseStrictVersions", false) || dep.has("thirdPartyCompatibility"))
                    throw fail("Dependency requires capability/platform/classifier selection not represented by the native library profile");
                JSONObject attrs = dep.optJSONObject("attributes");
                if (attrs != null && attrs.length() != 0) throw fail("Dependency-specific variant attributes require an explicit native profile");
                JSONObject version = dep.getJSONObject("version");
                String chosen = version.optString("strictly", version.optString("requires", version.optString("prefers", "")));
                MavenCoordinate value = new MavenCoordinate(dep.getString("group"), dep.getString("module"), chosen, "");
                Set<String> excluded = new LinkedHashSet<>();
                JSONArray excludes = dep.optJSONArray("excludes");
                if (excludes != null) for (int j = 0; j < excludes.length(); j++) {
                    JSONObject exclude = excludes.getJSONObject(j);
                    String rule = exclude.optString("group", "*") + ":" + exclude.optString("module", "*");
                    if (!rule.matches("(?:[A-Za-z0-9_.-]+|\\*):(?:[A-Za-z0-9_.-]+|\\*)")) throw fail("Invalid module exclusion");
                    excluded.add(rule);
                }
                dependencies.add(new Dependency(value, excluded));
                constraints.add(constraint(dep, false));
            }
            JSONArray declared = best.optJSONArray("dependencyConstraints");
            if (declared != null) for (int i = 0; i < declared.length(); i++) constraints.add(constraint(declared.getJSONObject(i), true));
            return new Variant(name, redirect, files, dependencies, constraints);
        } catch (JSONException | IllegalArgumentException error) { throw fail("Invalid variant: " + error.getMessage()); }
    }
    private Constraint constraint(JSONObject dep, boolean explicit) throws IOException, JSONException {
        JSONObject version = dep.getJSONObject("version");
        String group = dep.getString("group"), artifact = dep.getString("module");
        String required = explicit ? version.optString("requires", "") : "";
        String strict = version.optString("strictly", ""), preferred = version.optString("prefers", "");
        for (String value : Arrays.asList(required, strict, preferred)) if (!value.isEmpty()) new MavenCoordinate(group, artifact, value, "");
        Set<String> rejected = new LinkedHashSet<>();
        JSONArray rejects = version.optJSONArray("rejects");
        if (rejects != null) for (int i = 0; i < rejects.length(); i++) {
            String value = rejects.getString(i); new MavenCoordinate(group, artifact, value, ""); rejected.add(value);
        }
        return new Constraint(group + ":" + artifact, required, strict, preferred, rejected);
    }
    private boolean defaultCapability(JSONArray capabilities) throws JSONException {
        if (capabilities == null) return true;
        for (int i = 0; i < capabilities.length(); i++) {
            JSONObject capability = capabilities.getJSONObject(i);
            boolean requested = coordinate.group.equals(capability.getString("group")) && coordinate.artifact.equals(capability.getString("name")) && coordinate.version.equals(capability.getString("version"));
            boolean owned = owner.group.equals(capability.getString("group")) && owner.artifact.equals(capability.getString("name")) && owner.version.equals(capability.getString("version"));
            if (!requested && !owned) return false;
        }
        return true;
    }
    private void validateAttributes(JSONObject attrs) throws IOException {
        Set<String> known = new HashSet<>(Arrays.asList("org.gradle.usage", "org.gradle.category", "org.gradle.libraryelements",
            "org.gradle.dependency.bundling", "org.gradle.jvm.version", "org.gradle.jvm.environment", "org.jetbrains.kotlin.platform.type",
            "com.android.build.api.attributes.BuildTypeAttr", "com.android.build.api.attributes.AgpVersionAttr", "org.gradle.status"));
        Iterator<String> keys = attrs.keys();
        while (keys.hasNext()) { String key = keys.next(); if (!known.contains(key)) throw fail("Unsupported variant attribute: " + key); }
    }
    private static boolean nonempty(JSONObject object, String key) {
        JSONArray array = object.optJSONArray(key); return array != null && array.length() > 0;
    }
    private IOException fail(String message) { return new IOException("Module " + coordinate + ": " + message); }
}
