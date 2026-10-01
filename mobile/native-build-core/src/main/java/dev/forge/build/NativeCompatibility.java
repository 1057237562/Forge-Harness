package dev.forge.build;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Static compatibility review and non-overwriting migration; no script evaluation or dependency download. */
public final class NativeCompatibility {
    public static final class Report {
        public final File root;
        public final boolean configurationSupported, profileMatches;
        public final String summary, migrationJson, inputHash;
        public final List<String> notices;
        Report(File root, boolean supported, boolean matches, String summary, String migration, String hash, List<String> notices) {
            this.root = root; configurationSupported = supported; profileMatches = matches; this.summary = summary;
            migrationJson = migration; inputHash = hash; this.notices = Collections.unmodifiableList(new ArrayList<>(notices));
        }
    }
    public static Report inspect(File root, int installedSdk) {
        try {
            ProjectModel model = new ProjectInspector().inspect(root);
            boolean matches = model.compileSdk == installedSdk;
            String summary = model.applicationId + (model.kotlinRoots.isEmpty() ? " · Java 8" : " · Java 8 / Kotlin 1.9.24") + " · SDK " + model.compileSdk +
                (matches ? "\nConfiguration is supported. Dependency resolution and a real build are still required." : "\nInstalled native profile provides SDK " + installedSdk + "; this project cannot build with that profile.");
            String migration = WorkspaceFiles.resolve(root, ".forge/project.json").exists() ? null : json(model);
            return new Report(root, true, matches, summary, migration, InputSnapshot.digest(InputSnapshot.hashInputs(model)), model.notices);
        } catch (Exception error) {
            return new Report(root, false, false, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), null, null, Collections.emptyList());
        }
    }
    public static void apply(Report report) throws IOException {
        if (!report.configurationSupported || !report.profileMatches || report.migrationJson == null) throw new IOException("No applicable migration preview");
        File target = WorkspaceFiles.resolve(report.root, ".forge/project.json");
        if (target.exists()) throw new IOException("Forge configuration already exists; it will not be overwritten");
        String current = InputSnapshot.digest(InputSnapshot.hashInputs(new ProjectInspector().inspect(report.root)));
        if (!current.equals(report.inputHash)) throw new IOException("Project changed since the compatibility review; review again");
        Files.createDirectories(target.toPath().getParent());
        WorkspaceFiles.resolve(report.root, ".forge/project.json");
        // Android app storage can forbid hard links. CREATE_NEW never replaces an existing name.
        // A crash can leave a partial new config (which inspection rejects), but cannot overwrite old data.
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(report.migrationJson.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }
    private static String json(ProjectModel model) {
        JSONObject json = new JSONObject().put("schemaVersion", 1).put("name", model.name).put("applicationId", model.applicationId)
            .put("namespace", model.namespace).put("compileSdk", model.compileSdk).put("minSdk", model.minSdk).put("targetSdk", model.targetSdk)
            .put("versionCode", model.versionCode).put("versionName", model.versionName).put("javaVersion", "8")
            .put("manifest", model.manifest).put("java", new JSONArray(model.javaRoots)).put("resources", new JSONArray(model.resourceRoots))
            .put("kotlin", new JSONArray(model.kotlinRoots))
            .put("assets", new JSONArray(model.assetRoots)).put("repositories", new JSONArray(model.repositories));
        JSONArray deps = new JSONArray();
        for (ProjectModel.Dependency dependency : model.dependencies) {
            String scope;
            switch (dependency.scope) {
                case API: scope = "api"; break;
                case COMPILE_ONLY: scope = "compileOnly"; break;
                case RUNTIME_ONLY: scope = "runtimeOnly"; break;
                default: scope = "implementation";
            }
            deps.put(new JSONObject().put("scope", scope).put(dependency.local ? "path" : "coordinate", dependency.value)
                .put("excludes", new JSONArray(dependency.excludes)));
        }
        return json.put("dependencies", deps).toString(2) + "\n";
    }
}
