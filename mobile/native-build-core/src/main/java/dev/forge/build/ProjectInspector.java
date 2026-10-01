package dev.forge.build;

import java.io.*;
import java.net.URI;
import java.util.*;
import org.json.*;
import static dev.forge.build.GradleSyntax.*;

/** Read-only importer. Unsupported DSL is reported before any compiler or download runs. */
public final class ProjectInspector {
    private static final List<String> DEFAULT_REPOSITORIES = Arrays.asList(
        "https://dl.google.com/dl/android/maven2/", "https://repo.maven.apache.org/maven2/");
    private static final Set<String> CONFIG_KEYS = new HashSet<>(Arrays.asList(
        "schemaVersion", "name", "applicationId", "namespace", "compileSdk", "minSdk", "targetSdk",
        "versionCode", "versionName", "manifest", "java", "kotlin", "resources", "assets", "dependencies", "repositories", "javaVersion"));

    public ProjectModel inspect(File inputRoot) throws IOException {
        File root = WorkspaceFiles.resolve(inputRoot, ".");
        if (!root.isDirectory()) throw new IOException("Project directory does not exist: " + root);
        File config = WorkspaceFiles.resolve(root, ".forge/project.json");
        if (config.isFile()) return explicit(root, config);
        validateProperties(root);
        String prefix;
        if (WorkspaceFiles.resolve(root, "src/main/AndroidManifest.xml").isFile()) prefix = "";
        else if (WorkspaceFiles.resolve(root, "app/src/main/AndroidManifest.xml").isFile()) prefix = "app/";
        else if (WorkspaceFiles.resolve(root, "AndroidManifest.xml").isFile()) return legacy(root);
        else throw new CompatibilityException(root.getPath(), 0, "No Android manifest; create a Java/XML project or .forge/project.json");
        String path = prefix + "build.gradle";
        if (WorkspaceFiles.resolve(root, prefix + "build.gradle.kts").exists())
            throw new CompatibilityException(prefix + "build.gradle.kts", 0, "Kotlin DSL is not executed; provide an explicit .forge/project.json migration");
        File build = WorkspaceFiles.resolve(root, path);
        if (!build.isFile()) throw new CompatibilityException(path, 0, "Missing static module configuration; provide .forge/project.json");
        if (!prefix.isEmpty()) validateSettings(root);
        Draft draft = conventional(root, prefix, path);
        for (Statement s : parse(path, WorkspaceFiles.readUtf8(build, 1024 * 1024))) topLevel(draft, s);
        if (!draft.applicationPlugin) throw draft.error(0, "Only com.android.application projects are supported");
        return finish(draft);
    }

    private ProjectModel explicit(File root, File config) throws IOException {
        Draft d = new Draft(root, ".forge/project.json");
        try {
            JSONObject json = new JSONObject(WorkspaceFiles.readUtf8(config, 1024 * 1024));
            keys(json, CONFIG_KEYS, d.configuration);
            if (number(json, "schemaVersion", -1) != 1) throw d.error(0, "Unsupported schemaVersion");
            d.name = json.optString("name", root.getName());
            d.applicationId = json.getString("applicationId");
            d.namespace = json.optString("namespace", d.applicationId);
            d.compileSdk = number(json, "compileSdk", -1);
            d.minSdk = number(json, "minSdk", -1);
            d.targetSdk = number(json, "targetSdk", -1);
            d.versionCode = number(json, "versionCode", 1);
            d.versionName = json.optString("versionName", "1.0");
            if (!json.optString("javaVersion", "8").equals("8")) throw d.error(0, "This toolchain currently supports Java 8 only");
            d.manifest = json.getString("manifest");
            d.javaRoots.addAll(strings(json.getJSONArray("java")));
            d.kotlinRoots.addAll(strings(json.optJSONArray("kotlin")));
            d.resourceRoots.addAll(strings(json.optJSONArray("resources")));
            d.assetRoots.addAll(strings(json.optJSONArray("assets")));
            if (json.has("repositories")) { d.repositories.clear(); d.repositories.addAll(strings(json.getJSONArray("repositories"))); }
            JSONArray deps = json.optJSONArray("dependencies");
            if (deps != null) for (int i = 0; i < deps.length(); i++) {
                JSONObject dep = deps.getJSONObject(i);
                keys(dep, new HashSet<>(Arrays.asList("scope", "coordinate", "path", "excludes")), d.configuration);
                boolean local = dep.has("path");
                if (local == dep.has("coordinate")) throw d.error(0, "Dependency needs exactly one of coordinate/path");
                d.dependencies.add(new ProjectModel.Dependency(scope(dep.optString("scope", "implementation"), d, 0),
                    dep.getString(local ? "path" : "coordinate"), local, new LinkedHashSet<>(strings(dep.optJSONArray("excludes")))));
            }
            d.notices.add("Explicit Forge configuration is authoritative; Gradle scripts are not evaluated.");
            return finish(d);
        } catch (JSONException e) { throw d.error(0, "Invalid Forge configuration: " + e.getMessage()); }
    }

    private ProjectModel legacy(File root) throws IOException {
        Draft d = new Draft(root, "AndroidManifest.xml");
        d.manifest = "AndroidManifest.xml";
        ManifestInfo info = ManifestInfo.read(WorkspaceFiles.resolve(root, d.manifest));
        d.namespace = d.applicationId = info.packageName;
        d.minSdk = info.minSdk; d.targetSdk = info.targetSdk; d.compileSdk = 29;
        d.versionCode = info.versionCode; d.versionName = info.versionName;
        d.javaRoots.add("src"); d.resourceRoots.add("res"); d.assetRoots.add("assets");
        for (File lib : WorkspaceFiles.collect(root, "libs")) {
            String relative = root.toPath().relativize(lib.toPath()).toString().replace(File.separatorChar, '/');
            if (!relative.endsWith(".jar") && !relative.endsWith(".aar"))
                throw d.error(0, "Legacy native/unknown library requires explicit configuration: " + relative);
            d.dependencies.add(new ProjectModel.Dependency(ProjectModel.Scope.IMPLEMENTATION, relative, true));
        }
        d.notices.add("Legacy Java/XML layout imported with compileSdk 29. Use .forge/project.json to select another installed SDK.");
        return finish(d);
    }
    private Draft conventional(File root, String prefix, String configuration) throws IOException {
        Draft d = new Draft(root, configuration);
        d.manifest = prefix + "src/main/AndroidManifest.xml";
        ManifestInfo info = ManifestInfo.read(WorkspaceFiles.resolve(root, d.manifest));
        d.namespace = d.applicationId = info.packageName;
        d.minSdk = info.minSdk; d.targetSdk = info.targetSdk;
        d.versionCode = info.versionCode; d.versionName = info.versionName;
        d.javaRoots.add(prefix + "src/main/java");
        d.resourceRoots.add(prefix + "src/main/res"); d.assetRoots.add(prefix + "src/main/assets");
        for (String unsupported : Arrays.asList("kotlin", "aidl", "jniLibs", "cpp", "rs"))
            if (!WorkspaceFiles.collect(root, prefix + "src/main/" + unsupported).isEmpty())
                throw d.error(0, "Additional compiler support is required for src/main/" + unsupported);
        // Alternate source sets must never be silently left out of the resulting APK.
        File src = WorkspaceFiles.resolve(root, prefix + "src");
        File[] sets = src.listFiles();
        if (sets != null) for (File set : sets) if (set.isDirectory() &&
            !Arrays.asList("main", "test", "androidTest").contains(set.getName()))
            throw d.error(0, "Source set '" + set.getName() + "' requires an explicit migration; only main is supported yet");
        return d;
    }

    private void validateSettings(File root) throws IOException {
        File file = WorkspaceFiles.resolve(root, "settings.gradle");
        if (WorkspaceFiles.resolve(root, "settings.gradle.kts").exists())
            throw new CompatibilityException("settings.gradle.kts", 0, "Kotlin settings require explicit Forge project configuration");
        if (file.exists()) for (Statement statement : parse("settings.gradle", WorkspaceFiles.readUtf8(file, 1024 * 1024))) {
            if (statement.name().equals("include")) {
                if (statement.children != null) throw new CompatibilityException("settings.gradle", statement.line, "Dynamic module declarations are unsupported");
                for (Token token : statement.values()) if (!token.quoted || !Arrays.asList(":app", "app").contains(token.text))
                    throw new CompatibilityException("settings.gradle", token.line, "Multiple or dynamic modules need explicit migration");
            } else if (statement.name().equals("rootProject.name")) {
                if (statement.children != null || statement.values().size() != 1 || !statement.values().get(0).quoted)
                    throw new CompatibilityException("settings.gradle", statement.line, "Dynamic rootProject.name is unsupported");
            } else throw new CompatibilityException("settings.gradle", statement.line,
                "Unsupported settings declaration: " + statement.name() + "; use explicit Forge configuration");
        }
        if (WorkspaceFiles.resolve(root, "buildSrc").exists())
            throw new CompatibilityException("buildSrc", 0, "Custom Gradle code is not executed; explicit migration required");
        File rootBuild = WorkspaceFiles.resolve(root, "build.gradle");
        if (rootBuild.isFile()) for (Statement s : parse("build.gradle", WorkspaceFiles.readUtf8(rootBuild, 1024 * 1024))) {
            // Root plugins are descriptive only. Any behavior-changing root code must be migrated explicitly.
            if (!s.name().equals("plugins") || s.children == null)
                throw new CompatibilityException("build.gradle", s.line, "Root build logic requires explicit migration");
            for (Statement p : s.children) {
                List<Token> values = p.values();
                if (!p.name().equals("id") || values.isEmpty() || !values.get(0).quoted || !values.get(0).text.equals("com.android.application"))
                    throw new CompatibilityException("build.gradle", p.line, "Unsupported root plugin");
                validatePluginTail("build.gradle", p);
            }
        }
    }
    private void validateProperties(File root) throws IOException {
        File file = WorkspaceFiles.resolve(root, "gradle.properties");
        if (!file.isFile()) return;
        Properties properties = new Properties();
        properties.load(new StringReader(WorkspaceFiles.readUtf8(file, 1024 * 1024)));
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("org.gradle.") || name.equals("android.useAndroidX") || name.equals("kotlin.code.style")) continue;
            throw new CompatibilityException("gradle.properties", 0,
                "Property '" + name + "' needs explicit native configuration; it cannot be silently ignored");
        }
    }
    private void topLevel(Draft d, Statement s) throws IOException {
        if (s.name().equals("plugins")) {
            blockOnly(d, s);
            for (Statement p : s.children) {
                List<Token> values = p.values();
                if (!p.name().equals("id") || values.isEmpty() || !values.get(0).quoted || !values.get(0).text.equals("com.android.application"))
                    throw d.error(p.line, "Only the Android application plugin is supported; Kotlin/Compose and custom plugins require additional compiler support");
                validatePluginTail(d.configuration, p);
                for (Token value : values) if (!value.quoted && value.text.equals("apply"))
                    throw d.error(p.line, "Application plugin must be applied in this module");
                d.applicationPlugin = true;
            }
        } else if (s.name().equals("apply")) {
            List<Token> values = s.values();
            if (s.children != null || values.size() != 2 || !values.get(0).text.equals("plugin") ||
                !values.get(1).quoted || !values.get(1).text.equals("com.android.application"))
                throw d.error(s.line, "Unsupported apply statement");
            d.applicationPlugin = true;
        } else if (s.name().equals("android")) {
            blockOnly(d, s);
            for (Statement child : s.children) android(d, child);
        } else if (s.name().equals("dependencies")) {
            blockOnly(d, s);
            for (Statement child : s.children) {
                ProjectModel.Scope scope = scope(child.name(), d, child.line);
                List<Token> values = child.values();
                if (child.children != null || values.size() != 1 || !values.get(0).quoted)
                    throw d.error(child.line, "Only a fixed Maven coordinate is supported here; use Forge JSON for local libraries");
                d.dependencies.add(new ProjectModel.Dependency(scope, values.get(0).text, false));
            }
        } else if (s.name().equals("repositories")) {
            blockOnly(d, s); d.repositories.clear();
            for (Statement child : s.children) {
                if (child.children != null || !child.values().isEmpty()) throw d.error(child.line, "Custom repository declaration requires Forge JSON");
                if (child.name().equals("google")) d.repositories.add(DEFAULT_REPOSITORIES.get(0));
                else if (child.name().equals("mavenCentral")) d.repositories.add(DEFAULT_REPOSITORIES.get(1));
                else throw d.error(child.line, "Unsupported repository: " + child.name());
            }
        } else throw d.error(s.line, "Unsupported declaration: " + s.name() + "; native builds never execute Gradle code");
    }
    private static void validatePluginTail(String file, Statement statement) throws CompatibilityException {
        if (statement.children != null) throw new CompatibilityException(file, statement.line, "Plugin closures are unsupported");
        List<Token> values = statement.values();
        int index = 1;
        if (index < values.size() && values.get(index).text.equals("version")) {
            index++;
            if (index >= values.size() || !values.get(index).quoted) throw new CompatibilityException(file, statement.line, "Dynamic plugin version");
            index++;
        }
        if (index < values.size() && values.get(index).text.equals("apply")) {
            index++;
            if (index >= values.size() || !values.get(index).text.equals("false")) throw new CompatibilityException(file, statement.line, "Unsupported plugin apply value");
            index++;
        }
        if (index != values.size()) throw new CompatibilityException(file, statement.line, "Unsupported plugin expression");
    }

    private void android(Draft d, Statement s) throws IOException {
        switch (s.name()) {
            case "namespace": d.namespace = string(d, s); return;
            case "compileSdk": case "compileSdkVersion": d.compileSdk = integer(d, s); return;
            case "buildToolsVersion":
                d.notices.add("Requested buildToolsVersion " + string(d, s) + " is replaced by the selected native toolchain profile."); return;
            case "defaultConfig":
                blockOnly(d, s);
                for (Statement child : s.children) {
                    switch (child.name()) {
                        case "applicationId": d.applicationId = string(d, child); break;
                        case "minSdk": case "minSdkVersion": d.minSdk = integer(d, child); break;
                        case "targetSdk": case "targetSdkVersion": d.targetSdk = integer(d, child); break;
                        case "versionCode": d.versionCode = integer(d, child); break;
                        case "versionName": d.versionName = string(d, child); break;
                        case "testInstrumentationRunner": string(d, child); d.notices.add("Instrumentation runner is not part of debug assemble."); break;
                        default: throw d.error(child.line, "Unsupported defaultConfig value: " + child.name());
                    }
                } return;
            case "compileOptions":
                blockOnly(d, s);
                for (Statement child : s.children) {
                    if (!Arrays.asList("sourceCompatibility", "targetCompatibility").contains(child.name())) throw d.error(child.line, "Unsupported compile option");
                    String value = scalar(d, child).text;
                    if (!Arrays.asList("JavaVersion.VERSION_1_8", "JavaVersion.VERSION_8", "1.8", "8").contains(value))
                        throw d.error(child.line, "This native profile supports Java 8 only");
                } return;
            case "buildTypes":
                blockOnly(d, s);
                for (Statement type : s.children) {
                    blockOnly(d, type);
                    if (type.name().equals("release")) { d.notices.add("Release configuration not used for the debug variant."); continue; }
                    if (!type.name().equals("debug")) throw d.error(type.line, "Custom build types require explicit migration");
                    for (Statement option : type.children) {
                        if (!Arrays.asList("minifyEnabled", "shrinkResources").contains(option.name()) || scalar(d, option).quoted || !scalar(d, option).text.equals("false"))
                            throw d.error(option.line, "Unsupported debug option: " + option.name());
                    }
                } return;
            default: throw d.error(s.line, "Unsupported android configuration: " + s.name() + "; migrate explicitly rather than losing its behavior");
        }
    }
    private static Token scalar(Draft d, Statement s) throws CompatibilityException {
        List<Token> values = s.values();
        if (s.children != null || values.size() != 1) throw d.error(s.line, "Expected one constant value for " + s.name());
        return values.get(0);
    }
    private static String string(Draft d, Statement s) throws CompatibilityException {
        Token value = scalar(d, s);
        if (!value.quoted) throw d.error(s.line, "Expected a quoted constant for " + s.name());
        return value.text;
    }
    private static int integer(Draft d, Statement s) throws CompatibilityException {
        Token value = scalar(d, s);
        try { return Integer.parseInt(value.text); }
        catch (NumberFormatException e) { throw d.error(s.line, "Expected a constant integer for " + s.name()); }
    }
    private static void blockOnly(Draft d, Statement s) throws CompatibilityException {
        if (s.children == null || !s.values().isEmpty()) throw d.error(s.line, "Expected a plain " + s.name() + " block");
    }
    private static ProjectModel.Scope scope(String value, Draft d, int line) throws CompatibilityException {
        switch (value) {
            case "implementation": return ProjectModel.Scope.IMPLEMENTATION;
            case "api": return ProjectModel.Scope.API;
            case "compileOnly": return ProjectModel.Scope.COMPILE_ONLY;
            case "runtimeOnly": return ProjectModel.Scope.RUNTIME_ONLY;
            default: throw d.error(line, "Unsupported dependency scope: " + value);
        }
    }
    private ProjectModel finish(Draft d) throws IOException {
        if (d.namespace.isEmpty()) d.namespace = d.applicationId;
        if (d.applicationId.isEmpty()) d.applicationId = d.namespace;
        for (String id : Arrays.asList(d.applicationId, d.namespace))
            if (!id.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) throw d.error(0, "Invalid/missing Android package or namespace: " + id);
        if (d.compileSdk < 21 || d.minSdk < 21 || d.targetSdk < d.minSdk || d.compileSdk < d.targetSdk || d.versionCode < 1)
            throw d.error(0, "Expected compileSdk >= targetSdk >= minSdk >= 21 and positive versionCode");
        File manifest = WorkspaceFiles.resolve(d.root, d.manifest);
        if (!manifest.isFile()) throw d.error(0, "Missing manifest: " + d.manifest);
        ManifestInfo.read(manifest);
        if (d.javaRoots.isEmpty() && d.kotlinRoots.isEmpty()) throw d.error(0, "At least one Java or Kotlin source directory is required");
        Set<String> roots = new HashSet<>();
        Set<String> sourceRoots = new HashSet<>();
        for (List<String> dirs : Arrays.asList(d.javaRoots, d.kotlinRoots, d.resourceRoots, d.assetRoots)) {
          Set<String> inGroup = new HashSet<>();
          for (String dir : dirs) {
            File resolved = WorkspaceFiles.resolve(d.root, dir);
            boolean source = dirs == d.javaRoots || dirs == d.kotlinRoots;
            if (!inGroup.add(resolved.getPath()) || (!roots.add(resolved.getPath()) && !(source && sourceRoots.contains(resolved.getPath())))) throw d.error(0, "Duplicate input directory: " + dir);
            if (source) sourceRoots.add(resolved.getPath());
            if (resolved.exists() && !resolved.isDirectory()) throw d.error(0, "Expected a directory: " + dir);
            for (File input : WorkspaceFiles.collect(d.root, dir))
                if (source && (input.getName().endsWith(".kts") || (input.getName().endsWith(".kt") && d.kotlinRoots.isEmpty())))
                    throw d.error(0, "Kotlin sources require explicit kotlin directories; scripts are unsupported: " + input.getName());
          }
        }
        for (ProjectModel.Dependency dep : d.dependencies) {
            for (String rule : dep.excludes) if (!rule.matches("(?:[A-Za-z0-9_.-]+|\\*):(?:[A-Za-z0-9_.-]+|\\*)"))
                throw d.error(0, "Exclusion must be group:artifact (wildcards allowed): " + rule);
            if (dep.local) {
                File file = WorkspaceFiles.resolve(d.root, dep.value);
                if (!file.isFile() || !(dep.value.endsWith(".jar") || dep.value.endsWith(".aar")))
                    throw d.error(0, "Local dependency must be an existing JAR/AAR: " + dep.value);
            } else if (!dep.value.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+") || dep.value.endsWith("-SNAPSHOT"))
                throw d.error(0, "Dependency requires a fixed g:a:v coordinate: " + dep.value);
            else MavenCoordinate.parse(dep.value);
        }
        for (String repo : d.repositories) {
            try {
                URI uri = new URI(repo);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                    throw new IllegalArgumentException();
            } catch (Exception e) { throw d.error(0, "Repository must be an HTTPS base URL without credentials/query: " + repo); }
        }
        d.notices.add("Native " + (d.kotlinRoots.isEmpty() ? "Java/XML" : "Java/Kotlin/XML") + " debug build; Gradle scripts and plugins are not executed.");
        return new ProjectModel(d.root, d.name, d.applicationId, d.namespace, d.compileSdk, d.minSdk, d.targetSdk,
            d.versionCode, d.versionName, d.manifest, d.javaRoots, d.resourceRoots, d.assetRoots,
            d.dependencies, d.repositories, d.configuration, d.notices, d.kotlinRoots);
    }
    private static void keys(JSONObject object, Set<String> allowed, String file) throws CompatibilityException {
        Iterator<String> names = object.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) throw new CompatibilityException(file, 0, "Unknown configuration key: " + name);
        }
    }
    private static List<String> strings(JSONArray values) throws JSONException {
        List<String> result = new ArrayList<>();
        if (values != null) for (int i = 0; i < values.length(); i++) {
            if (!(values.get(i) instanceof String)) throw new JSONException("Expected a string array");
            result.add(values.getString(i));
        }
        return result;
    }
    private static int number(JSONObject object, String name, int fallback) throws JSONException {
        if (!object.has(name)) return fallback;
        Object value = object.get(name);
        if (!(value instanceof Number)) throw new JSONException(name + " must be an integer");
        double n = ((Number) value).doubleValue();
        if (n != Math.rint(n) || n > Integer.MAX_VALUE || n < Integer.MIN_VALUE)
            throw new JSONException(name + " must be an integer");
        return (int) n;
    }
    private static final class Draft {
        final File root;
        final String configuration;
        String name, applicationId = "", namespace = "", manifest, versionName = "1.0";
        int compileSdk = 0, minSdk = 21, targetSdk = 29, versionCode = 1;
        boolean applicationPlugin;
        final List<String> javaRoots = new ArrayList<>(), resourceRoots = new ArrayList<>(), assetRoots = new ArrayList<>();
        final List<String> kotlinRoots = new ArrayList<>();
        final List<String> repositories = new ArrayList<>(DEFAULT_REPOSITORIES), notices = new ArrayList<>();
        final List<ProjectModel.Dependency> dependencies = new ArrayList<>();
        Draft(File root, String configuration) { this.root = root; this.configuration = configuration; this.name = root.getName(); }
        CompatibilityException error(int line, String reason) { return new CompatibilityException(configuration, line, reason); }
    }
}
