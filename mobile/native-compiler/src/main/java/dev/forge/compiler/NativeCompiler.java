package dev.forge.compiler;

import dev.forge.build.*;
import com.android.apksig.*;
import com.android.tools.r8.*;
import org.eclipse.jdt.core.compiler.*;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.batch.Main;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Reusable native compiler. Inputs come from a validated model/snapshot, never a hardcoded fixture. */
public final class NativeCompiler {
    public BuildOutcome build(File project, File output, File cache, NativeToolchain tools,
                              Cancellation cancellation, BuildListener listener) throws Exception {
        return build(project, output, cache, tools, cancellation, listener, false);
    }
    public BuildOutcome build(File project, File output, File cache, NativeToolchain tools,
                              Cancellation cancellation, BuildListener listener, boolean offline) throws Exception {
        return new Operation(project, output, cache, tools, cancellation, listener, offline).run();
    }
    private interface Step { void run() throws Exception; }
    private static final class Operation {
        final File projectRoot, output, cache;
        final NativeToolchain tools;
        final Cancellation cancellation;
        final BuildListener listener;
        final Map<String, Long> times = new LinkedHashMap<>();
        final Map<String, Integer> stageCacheHits = new LinkedHashMap<>();
        String toolFingerprint;
        final Map<String, String> cachedResourceOrigins = new LinkedHashMap<>();
        ProjectModel original, model;
        InputSnapshot snapshot;
        MavenResolver.Resolution dependencies;
        final boolean offline;
        final List<File> compileJars = new ArrayList<>(), runtimeJars = new ArrayList<>();
        final List<AarLibrary> libraries = new ArrayList<>();
        Operation(File project, File output, File cache, NativeToolchain tools, Cancellation token, BuildListener listener, boolean offline) {
            this.projectRoot = project; this.output = output; this.cache = cache; this.tools = tools;
            this.cancellation = token;
            this.offline = offline;
            this.listener = new BuildListener() {
                public void log(String text) { listener.log(sourcePath(text)); }
                public void stage(String name, boolean starting, long duration) { listener.stage(name, starting, duration); }
                public void diagnostic(String severity, String file, int line, int column, String message) {
                    listener.diagnostic(severity, sourcePath(file), line, column, sourcePath(message));
                }
            };
        }
        BuildOutcome run() throws Exception {
            cancellation.check();
            if (!output.mkdirs()) throw new IOException("Build output must be a new directory: " + output);
            stage("inspect", () -> {
                original = new ProjectInspector().inspect(projectRoot);
                if (!original.kotlinRoots.isEmpty() && (tools.kotlin == null || tools.kotlinStdlib == null || !tools.kotlinStdlib.isFile()))
                    throw new CompatibilityException(original.configuration, 0, "Kotlin backend and matching standard library are not installed");
                if (original.compileSdk != tools.sdk) throw new CompatibilityException(original.configuration, 0,
                    "Project needs SDK " + original.compileSdk + "; selected native profile provides SDK " + tools.sdk);
                for (String notice : original.notices) listener.log(notice);
            });
            stage("snapshot", () -> {
                snapshot = InputSnapshot.capture(original, file("snapshot"));
                model = new ProjectInspector().inspect(snapshot.directory);
            });
            stage("dependencies", () -> {
                RepositoryCache repository = new RepositoryCache(new File(cache, "maven"), new RepositoryCache.HttpsTransport(),
                    offline, cancellation::check, listener::log);
                dependencies = new MavenResolver(repository, model.repositories, cancellation::check).resolve(model);
                for (String notice : dependencies.notices) listener.log(notice);
                for (MavenResolver.Artifact dependency : dependencies.artifacts) {
                    if (!model.kotlinRoots.isEmpty() && dependency.id.startsWith("org.jetbrains.kotlin:kotlin-stdlib")) {
                        if (!dependency.id.matches("org\\.jetbrains\\.kotlin:kotlin-stdlib(?:-common|-jdk7|-jdk8)?:1\\.9\\.24(?:@.*)?"))
                            throw new CompatibilityException(model.configuration, 0, "Pin Kotlin runtime dependencies to the native compiler profile 1.9.24: " + dependency.id);
                        if (dependency.id.startsWith("org.jetbrains.kotlin:kotlin-stdlib:")) {
                            if (!dependency.sha256.equals(WorkspaceFiles.sha256(tools.kotlinStdlib))) throw new IOException("Kotlin standard library differs from the verified bundled profile");
                            continue;
                        }
                    }
                    if (dependency.type.equals("aar")) {
                        if (!dependency.runtime) throw new CompatibilityException(model.configuration, 0, "compileOnly AAR resources need explicit compile/runtime separation: " + dependency.id);
                        AarLibrary library = AarLibrary.extract(dependency.file, file("aar/" + libraries.size()), model.compileSdk, cancellation);
                        libraries.add(library);
                        if (dependency.compile) compileJars.addAll(library.jars);
                        runtimeJars.addAll(library.jars);
                    } else {
                        if (dependency.compile) compileJars.add(dependency.file);
                        if (dependency.runtime) runtimeJars.add(dependency.file);
                    }
                }
                if (!model.kotlinRoots.isEmpty()) { compileJars.add(tools.kotlinStdlib); runtimeJars.add(tools.kotlinStdlib); }
            });
            Map<String, String> cacheInputs = new TreeMap<>();
            cacheInputs.put("inputs", snapshot.sha256);
            toolFingerprint = tools.fingerprint();
            cacheInputs.put("tools", toolFingerprint);
            cacheInputs.put("dependencies", dependencies.fingerprint);
            cacheInputs.put("engine", "native-java-v4");
            String key = InputSnapshot.digest(cacheInputs);
            File cached = WorkspaceFiles.resolve(cache, key + "/app.apk");
            File cacheHash = WorkspaceFiles.resolve(cache, key + "/app.sha256");
            if (cached.isFile() && cacheHash.isFile() && WorkspaceFiles.readUtf8(cacheHash, 256).trim().equals(WorkspaceFiles.sha256(cached))) {
                stage("cache-hit", () -> Files.copy(cached.toPath(), file("app.apk").toPath()));
                stage("verify", () -> verify(file("app.apk")));
                ensureUnchanged();
                return outcome(true);
            }
            directory("generated"); directory("classes"); directory("dex");
            stage("manifest", () -> {
                if (libraries.isEmpty()) ManifestPreparer.prepare(model, file("AndroidManifest.xml"));
                else {
                    ManifestPreparer.prepare(model, file("main-manifest.xml"));
                    List<File> manifests = new ArrayList<>();
                    for (AarLibrary library : libraries) manifests.add(library.manifest);
                    Collections.reverse(manifests);
                    LibraryManifestMerger.merge(file("main-manifest.xml"), manifests, file("AndroidManifest.xml"), model.applicationId, model.minSdk, model.targetSdk, listener);
                }
            });
            stage("resources", () -> {
                List<String> link = new ArrayList<>(Arrays.asList("link", "-o", path("resources.ap_"), "--manifest", path("AndroidManifest.xml"),
                    "-I", tools.androidJar.getAbsolutePath(), "--java", path("generated"), "--custom-package", model.namespace,
                    "--output-text-symbols", path("R.txt"),
                    "--min-sdk-version", String.valueOf(model.minSdk), "--target-sdk-version", String.valueOf(model.targetSdk), "--auto-add-overlay"));
                int index = 0;
                Set<String> packages = new LinkedHashSet<>();
                for (AarLibrary library : libraries) {
                    packages.add(library.packageName);
                    if (WorkspaceFiles.collect(library.directory, "res").isEmpty()) continue;
                    String compiled = compileResources(new File(library.directory, "res"), index++);
                    link.add("-R"); link.add(compiled);
                }
                packages.remove(model.namespace);
                if (!packages.isEmpty()) { link.add("--extra-packages"); link.add(String.join(":", packages)); }
                for (String root : model.resourceRoots) {
                    if (WorkspaceFiles.collect(model.root, root).isEmpty()) continue;
                    File res = WorkspaceFiles.resolve(model.root, root);
                    String compiled = compileResources(res, index++);
                    link.add("-R"); link.add(compiled);
                }
                tool(tools.aapt2, link.toArray(new String[0]));
                LibraryRGenerator.write(libraries, file("R.txt"), file("generated"), model.namespace);
            });
            stage("java", this::compileJava);
            stage("dex", this::dex);
            stage("package", () -> ApkAssembler.pack(model, file("resources.ap_"), file("dex"), file("classes"), runtimeJars, libraries, file("unsigned.apk"), cancellation));
            stage("align", () -> tool(tools.zipalign, "-f", "4", path("unsigned.apk"), path("aligned.apk")));
            stage("sign", () -> {
                ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("forge-debug", tools.signingKey,
                    Collections.singletonList(tools.certificate)).build();
                new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(file("aligned.apk")).setOutputApk(file("app.apk"))
                    .setMinSdkVersion(model.minSdk).setV1SigningEnabled(true).setV2SigningEnabled(true)
                    .setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign();
            });
            stage("verify", () -> verify(file("app.apk")));
            ensureUnchanged();
            // Cache is only populated after all stages and source-consistency checks have passed.
            stage("cache-store", () -> {
                Files.createDirectories(cached.toPath().getParent());
                Path temp = Files.createTempFile(cached.toPath().getParent(), "artifact-", ".tmp");
                try {
                    Files.copy(file("app.apk").toPath(), temp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temp, cached.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temp); }
                WorkspaceFiles.atomicWrite(cacheHash, WorkspaceFiles.sha256(cached).getBytes(StandardCharsets.UTF_8));
            });
            return outcome(false);
        }
        void compileJava() throws Exception {
            File generated = file("generated/" + model.namespace.replace('.', '/') + "/BuildConfig.java");
            WorkspaceFiles.atomicWrite(generated, ("package " + model.namespace + ";\npublic final class BuildConfig {\n" +
                "public static final boolean DEBUG=true;\npublic static final String APPLICATION_ID=" + quote(model.applicationId) + ";\n" +
                "public static final String BUILD_TYPE=\"debug\";\npublic static final String FLAVOR=\"\";\n" +
                "public static final int VERSION_CODE=" + model.versionCode + ";\npublic static final String VERSION_NAME=" + quote(model.versionName) + ";\n}\n").getBytes(StandardCharsets.UTF_8));
            Map<String, String> javaInputs = stageInputs("java");
            for (int i = 0; i < model.javaRoots.size(); i++) addTree(javaInputs, "source:" + i, WorkspaceFiles.resolve(model.root, model.javaRoots.get(i)));
            for (int i = 0; i < model.kotlinRoots.size(); i++) addTree(javaInputs, "kotlin:" + i, WorkspaceFiles.resolve(model.root, model.kotlinRoots.get(i)));
            addTree(javaInputs, "generated", file("generated"));
            for (int i = 0; i < compileJars.size(); i++) javaInputs.put("classpath:" + i, WorkspaceFiles.sha256(compileJars.get(i)));
            String javaKey = InputSnapshot.digest(javaInputs);
            if (restoreStage("java", javaKey, file("classes"))) return;
            final java.util.concurrent.atomic.AtomicBoolean hasDiagnostics = new java.util.concurrent.atomic.AtomicBoolean();
            SortedSet<File> javaSources = new TreeSet<>(Comparator.comparing(File::getAbsolutePath));
            SortedSet<File> kotlinSources = new TreeSet<>(Comparator.comparing(File::getAbsolutePath));
            Set<String> sourceRoots = new LinkedHashSet<>(model.javaRoots); sourceRoots.addAll(model.kotlinRoots);
            for (String root : sourceRoots) for (File source : WorkspaceFiles.collect(model.root, root)) {
                if (source.getName().endsWith(".java")) javaSources.add(source);
                if (source.getName().endsWith(".kt")) kotlinSources.add(source);
            }
            for (File source : WorkspaceFiles.collect(output, "generated")) if (source.getName().endsWith(".java")) javaSources.add(source);
            if (!kotlinSources.isEmpty()) stage("kotlin", () -> tools.kotlin.compile(tools.androidJar, tools.kotlinStdlib, compileJars,
                new ArrayList<>(kotlinSources), new ArrayList<>(javaSources), file("classes"), file("kotlin-environment"), cancellation, new BuildListener() {
                    public void log(String message) { listener.log(message); }
                    public void diagnostic(String severity, String source, int line, int column, String message) {
                        hasDiagnostics.set(true); listener.diagnostic(severity, source, line, column, message);
                    }
                }));
            List<String> classpath = new ArrayList<>(); classpath.add(tools.androidJar.getAbsolutePath());
            if (!kotlinSources.isEmpty()) classpath.add(path("classes"));
            for (File jar : compileJars) classpath.add(jar.getAbsolutePath());
            List<String> args = new ArrayList<>(Arrays.asList("-source", "1.8", "-target", "1.8", "-proc:none", "-encoding", "UTF-8",
                "-bootclasspath", tools.androidJar.getAbsolutePath(), "-classpath", String.join(File.pathSeparator, classpath), "-d", path("classes")));
            for (File source : javaSources) args.add(source.getAbsolutePath());
            StringWriter log = new StringWriter();
            PrintWriter writer = new PrintWriter(log);
            CompilationProgress progress = new CompilationProgress() {
                public void begin(int remaining) { }
                public void done() { }
                public boolean isCanceled() { return cancellation.isCancelled(); }
                public void setTaskName(String name) { }
                public void worked(int work, int remaining) { }
            };
            Main compiler = new Main(writer, writer, false, null, progress) {
                @Override public ICompilerRequestor getBatchRequestor() {
                    ICompilerRequestor delegate = super.getBatchRequestor();
                    return result -> {
                        CategorizedProblem[] problems = result.getProblems();
                        if (problems != null && problems.length > 0) hasDiagnostics.set(true);
                        if (problems != null) for (CategorizedProblem problem : problems)
                            listener.diagnostic(problem.isError() ? "error" : "warning", problem.getOriginatingFileName() == null ? null : new String(problem.getOriginatingFileName()),
                                problem.getSourceLineNumber(), 0, problem.getMessage());
                        delegate.acceptResult(result);
                    };
                }
            };
            boolean success = compiler.compile(args.toArray(new String[0]));
            writer.flush(); if (log.getBuffer().length() > 0) listener.log(log.toString());
            cancellation.check();
            if (!success) throw new IOException("Java compilation failed; see diagnostics");
            if (!hasDiagnostics.get()) { ensureUnchanged(); stageCache().store(javaKey, file("classes")); }
        }
        void dex() throws Exception {
            Map<String, String> dexInputs = stageInputs("dex");
            dexInputs.put("minSdk", String.valueOf(model.minSdk));
            addTree(dexInputs, "classes", file("classes"));
            for (int i = 0; i < runtimeJars.size(); i++) dexInputs.put("runtime:" + i, WorkspaceFiles.sha256(runtimeJars.get(i)));
            for (int i = 0; i < compileJars.size(); i++) dexInputs.put("classpath:" + i, WorkspaceFiles.sha256(compileJars.get(i)));
            String dexKey = InputSnapshot.digest(dexInputs);
            if (restoreStage("dex", dexKey, file("dex"))) return;
            final java.util.concurrent.atomic.AtomicBoolean hasDiagnostics = new java.util.concurrent.atomic.AtomicBoolean();
            DiagnosticsHandler diagnostics = new DiagnosticsHandler() {
                @Override public void error(Diagnostic d) { listener.diagnostic("error", null, 0, 0, d.getDiagnosticMessage()); }
                @Override public void warning(Diagnostic d) { hasDiagnostics.set(true); listener.diagnostic("warning", null, 0, 0, d.getDiagnosticMessage()); }
                @Override public void info(Diagnostic d) { listener.log(d.getDiagnosticMessage()); }
            };
            D8Command.Builder command = D8Command.builder(diagnostics);
            if (runtimeJars.isEmpty()) {
                for (File file : WorkspaceFiles.collect(output, "classes")) if (file.getName().endsWith(".class")) command.addProgramFiles(file.toPath());
            } else {
                List<File> dependencyOutputs = dependencyDex();
                File applicationDex = file("application-dex"); Files.createDirectories(applicationDex.toPath());
                D8Command.Builder application = D8Command.builder(diagnostics);
                for (File file : WorkspaceFiles.collect(output, "classes")) if (file.getName().endsWith(".class")) application.addProgramFiles(file.toPath());
                Set<File> classpath = new LinkedHashSet<>(compileJars); classpath.addAll(runtimeJars);
                for (File jar : classpath) application.addClasspathFiles(jar.toPath());
                application.addLibraryFiles(tools.androidJar.toPath()).setMinApiLevel(model.minSdk).setMode(CompilationMode.DEBUG)
                    .setIntermediate(true).setOutput(applicationDex.toPath(), OutputMode.DexIndexed);
                runD8(application);
                for (File dex : WorkspaceFiles.collect(applicationDex, ".")) if (dex.getName().endsWith(".dex")) command.addProgramFiles(dex.toPath());
                for (File dex : dependencyOutputs) command.addProgramFiles(dex.toPath());
            }
            for (File jar : compileJars) if (!runtimeJars.contains(jar)) command.addClasspathFiles(jar.toPath());
            command.addLibraryFiles(tools.androidJar.toPath()).setMinApiLevel(model.minSdk).setMode(CompilationMode.DEBUG)
                .setOutput(file("dex").toPath(), OutputMode.DexIndexed);
            runD8(command);
            cancellation.check();
            if (!hasDiagnostics.get()) { ensureUnchanged(); stageCache().store(dexKey, file("dex")); }
        }
        void runD8(D8Command.Builder command) throws Exception {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try (AutoCloseable hook = cancellation.onCancel(workers::shutdownNow)) { D8.run(command.build(), workers); }
            finally { workers.shutdownNow(); }
            cancellation.check();
        }
        List<File> dependencyDex() throws Exception {
            Map<String, String> inputs = stageInputs("dependency-dex");
            inputs.put("minSdk", String.valueOf(model.minSdk));
            inputs.put("application-structure", ClassStructureHash.directory(file("classes"), cancellation));
            for (int i = 0; i < runtimeJars.size(); i++) inputs.put("runtime:" + i, WorkspaceFiles.sha256(runtimeJars.get(i)));
            for (int i = 0; i < compileJars.size(); i++) inputs.put("classpath:" + i, WorkspaceFiles.sha256(compileJars.get(i)));
            String key = InputSnapshot.digest(inputs);
            File directory = file("dependency-dex"); Files.createDirectories(directory.toPath());
            if (!restoreStage("dependency-dex", key, directory)) {
                final java.util.concurrent.atomic.AtomicBoolean warning = new java.util.concurrent.atomic.AtomicBoolean();
                D8Command.Builder command = D8Command.builder(new DiagnosticsHandler() {
                    public void error(Diagnostic d) { listener.diagnostic("error", null, 0, 0, d.getDiagnosticMessage()); }
                    public void warning(Diagnostic d) { warning.set(true); listener.diagnostic("warning", null, 0, 0, d.getDiagnosticMessage()); }
                });
                for (File jar : runtimeJars) command.addProgramFiles(jar.toPath());
                for (File jar : compileJars) if (!runtimeJars.contains(jar)) command.addClasspathFiles(jar.toPath());
                command.addClasspathFiles(file("classes").toPath());
                command.addLibraryFiles(tools.androidJar.toPath()).setMinApiLevel(model.minSdk).setMode(CompilationMode.DEBUG)
                    .setIntermediate(true).setOutput(directory.toPath(), OutputMode.DexIndexed);
                ExecutorService workers = Executors.newFixedThreadPool(2);
                try (AutoCloseable hook = cancellation.onCancel(workers::shutdownNow)) { D8.run(command.build(), workers); }
                finally { workers.shutdownNow(); }
                cancellation.check(); ensureUnchanged();
                if (!warning.get()) stageCache().store(key, directory);
            }
            List<File> dex = new ArrayList<>();
            for (File file : WorkspaceFiles.collect(directory, ".")) if (file.getName().endsWith(".dex")) dex.add(file);
            if (dex.isEmpty()) throw new IOException("Dependency D8 stage produced no dex files");
            return dex;
        }
        StageCache stageCache() { return new StageCache(new File(cache, "stages"), cancellation); }
        Map<String, String> stageInputs(String name) {
            Map<String, String> inputs = new TreeMap<>(); inputs.put("stage", name + "-v1"); inputs.put("toolchain", toolFingerprint); return inputs;
        }
        void addTree(Map<String, String> inputs, String label, File root) throws IOException {
            for (File item : WorkspaceFiles.collect(root, ".")) {
                cancellation.check();
                inputs.put(label + ":" + root.toPath().relativize(item.toPath()).toString().replace(File.separatorChar, '/'), WorkspaceFiles.sha256(item));
            }
        }
        boolean restoreStage(String name, String key, File destination) throws IOException {
            if (!stageCache().restore(key, destination)) return false;
            stageCacheHits.put(name, stageCacheHits.getOrDefault(name, 0) + 1);
            listener.log("STAGE CACHE HIT " + name); return true;
        }
        String compileResources(File resourceRoot, int index) throws Exception {
            File compiled = file("compiled-res-" + index); Files.createDirectories(compiled.toPath());
            Map<String, String> inputs = stageInputs("aapt2-compile"); addTree(inputs, "res", resourceRoot);
            String key = InputSnapshot.digest(inputs);
            File archive = new File(compiled, "resources.zip");
            if (!restoreStage("resources", key, compiled)) {
                tool(tools.aapt2, "compile", "--dir", resourceRoot.getAbsolutePath(), "-o", archive.getAbsolutePath());
                WorkspaceFiles.atomicWrite(new File(compiled, "origin.txt"), resourceRoot.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
                ensureUnchanged();
                stageCache().store(key, compiled);
            }
            String origin = WorkspaceFiles.readUtf8(new File(compiled, "origin.txt"), 8192);
            cachedResourceOrigins.put(origin, resourceRoot.getAbsolutePath());
            return archive.getAbsolutePath();
        }
        void verify(File apk) throws Exception {
            ApkVerifier.Result result = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(model.minSdk).build().verify();
            if (!result.isVerified()) throw new IOException("Signature verification failed: " + result.getErrors());
            tool(tools.zipalign, "-c", "4", apk.getAbsolutePath());
        }
        void ensureUnchanged() throws IOException {
            cancellation.check();
            ProjectModel current = new ProjectInspector().inspect(projectRoot);
            if (!snapshot.stillMatches(current)) throw new IOException("Project changed during build; artifact is stale and must not be installed");
            for (MavenResolver.Artifact dependency : dependencies.artifacts)
                if (!WorkspaceFiles.sha256(dependency.file).equals(dependency.sha256))
                    throw new IOException("Dependency changed during build: " + dependency.id);
        }
        BuildOutcome outcome(boolean hit) throws IOException {
            return new BuildOutcome(file("app.apk"), WorkspaceFiles.sha256(file("app.apk")), snapshot.sha256, hit, times, dependencies, stageCacheHits);
        }
        void stage(String name, Step step) throws Exception {
            cancellation.check(); long start = System.nanoTime();
            listener.stage(name, true, 0); listener.log("START " + name);
            try { step.run(); cancellation.check(); }
            finally {
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                times.put(name, duration); listener.log("END " + name + " " + duration + "ms"); listener.stage(name, false, duration);
            }
        }
        void tool(File executable, String... args) throws Exception { ToolProcess.run(executable, output, tools.environment, cancellation, listener, args); }
        File file(String path) { return new File(output, path); }
        String sourcePath(String value) {
            if (value == null || snapshot == null) return value;
            for (Map.Entry<String, String> origin : cachedResourceOrigins.entrySet()) value = value.replace(origin.getKey(), origin.getValue());
            return value.replace(snapshot.directory.getAbsolutePath(), original.root.getAbsolutePath());
        }
        String path(String path) { return file(path).getAbsolutePath(); }
        void directory(String path) throws IOException { Files.createDirectories(file(path).toPath()); }
    }
    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
}
