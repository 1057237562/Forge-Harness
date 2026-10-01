package dev.forge.nativeprobe;

import android.content.Context;
import dev.forge.build.WorkspaceFiles;
import dev.forge.compiler.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/** Device harness only. The compiler itself now lives in the shared native-compiler module. */
public final class NativeBuildEngine {
    public interface Listener { void log(String line); }
    private final Context context;
    private final File root;
    private final Listener listener;
    private final Cancellation cancellation;
    private final JSONObject times = new JSONObject();
    private final JSONArray diagnostics = new JSONArray();
    private BuildOutcome outcome;
    NativeBuildEngine(Context context, File root, Listener listener, Cancellation cancellation) {
        this.context = context; this.root = root; this.listener = listener; this.cancellation = cancellation;
    }
    public JSONObject timings() { return times; }
    public JSONArray diagnostics() { return diagnostics; }
    public boolean cacheHit() { return outcome != null && outcome.cacheHit; }
    public String snapshotId() { return outcome == null ? null : outcome.sourceSnapshotId; }
    public File build(String scenario) throws Exception {
        if (!Arrays.asList("baseline", "java-change", "resource-change", "broken-java", "broken-resource", "gradle-layout", "explicit-config", "local-jar", "cancel-at-resources").contains(scenario))
            throw new IllegalArgumentException("Unknown probe scenario: " + scenario);
        File project = new File(root, "project");
        copyAssetTree("fixture", project);
        File androidJar = new File(context.getFilesDir(), "toolchain/android.jar");
        if (!androidJar.isFile()) copyAsset("toolchain/android.jar", androidJar);
        File source = new File(project, "src/dev/forge/sample/MainActivity.java");
        File strings = new File(project, "res/values/strings.xml");
        if (scenario.equals("java-change")) replace(source, "native-build-ok", "native-build-java-change-ok");
        if (scenario.equals("resource-change")) replace(strings, "Built on Android", "Resource change verified on Android");
        if (scenario.equals("broken-java")) replace(source, "setContentView(R.layout.main);", "missingMethodForProbe();");
        if (scenario.equals("broken-resource")) replace(strings, "</resources>", "");
        if (scenario.equals("cancel-at-resources")) replace(source, "native-build-ok", "cancelled-build");
        if (scenario.equals("gradle-layout")) {
            copyAssetTree("fixture/src", new File(project, "app/src/main/java"));
            copyAssetTree("fixture/res", new File(project, "app/src/main/res"));
            copyAsset("fixture/AndroidManifest.xml", new File(project, "app/src/main/AndroidManifest.xml"));
            write(new File(project, "settings.gradle"), "include ':app'\n");
            write(new File(project, "app/build.gradle"), "plugins { id 'com.android.application' }\nandroid {\n namespace 'dev.forge.sample'\n compileSdk 29\n defaultConfig {\n applicationId 'dev.forge.sample'\n minSdk 28\n targetSdk 29\n }\n}\n");
        }
        if (scenario.equals("explicit-config")) {
            write(new File(project, ".forge/project.json"), "{\"schemaVersion\":1,\"applicationId\":\"dev.forge.sample\",\"compileSdk\":29,\"minSdk\":28,\"targetSdk\":29,\"manifest\":\"AndroidManifest.xml\",\"java\":[\"src\"],\"resources\":[\"res\"]}");
        }
        if (scenario.equals("local-jar")) {
            copyAsset("test-libs/helper.jar", new File(project, "libs/helper.jar"));
            replace(source, "message.setContentDescription(\"native-build-ok\");",
                "message.setContentDescription(\"native-build-ok\");\n        message.setText(dev.forge.library.NativeHelper.message());");
        }
        listener.log("Source SHA-256: " + WorkspaceFiles.sha256(source));
        PrivateKey key;
        try (InputStream input = context.getAssets().open("toolchain/debug.pk8")) {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(input)));
        }
        X509Certificate certificate;
        try (InputStream input = context.getAssets().open("toolchain/debug.x509")) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        String compilerIdentity;
        try (InputStream input = context.getAssets().open("toolchain/compiler-identity.txt")) {
            compilerIdentity = new String(readAll(input), StandardCharsets.UTF_8).trim();
        }
        NativeToolchain tools = new NativeToolchain(29, androidJar, new File(nativeDir, "libforge_aapt2.so"),
            new File(nativeDir, "libforge_zipalign.so"), Collections.singletonMap("LD_LIBRARY_PATH", nativeDir.getAbsolutePath()),
            key, certificate, compilerIdentity);
        outcome = new NativeCompiler().build(project, new File(root, "build"), new File(context.getFilesDir(), "native-cache"), tools,
            cancellation, new BuildListener() {
                @Override public void log(String line) { listener.log(line); }
                @Override public void stage(String name, boolean starting, long ms) {
                    if (starting && name.equals("resources") && scenario.equals("cancel-at-resources")) cancellation.cancel();
                    if (!starting) try { times.put(name, ms); } catch (JSONException e) { throw new IllegalStateException(e); }
                }
                @Override public void diagnostic(String severity, String file, int line, int column, String message) {
                    synchronized (diagnostics) {
                        try { diagnostics.put(new JSONObject().put("severity", severity).put("file", file).put("line", line).put("column", column).put("message", message)); }
                        catch (JSONException e) { throw new IllegalStateException(e); }
                    }
                    listener.log(severity + " " + (file == null ? "" : file + ":" + line) + " " + message);
                }
            });
        return outcome.apk;
    }
    private void copyAssetTree(String asset, File destination) throws IOException {
        String[] names = context.getAssets().list(asset);
        if (names == null || names.length == 0) { copyAsset(asset, destination); return; }
        if (!destination.mkdirs() && !destination.isDirectory()) throw new IOException("Cannot create " + destination);
        for (String name : names) copyAssetTree(asset + "/" + name, new File(destination, name));
    }
    private void copyAsset(String asset, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create " + parent);
        try (InputStream input = context.getAssets().open(asset)) { Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void write(File file, String text) throws IOException {
        WorkspaceFiles.atomicWrite(file, text.getBytes(StandardCharsets.UTF_8));
    }
    private static void replace(File file, String before, String after) throws IOException {
        String text = WorkspaceFiles.readUtf8(file, 1024 * 1024);
        if (!text.contains(before)) throw new IOException("Fixture mutation anchor missing");
        write(file, text.replace(before, after));
    }
    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }
    public static String sha256(File file) throws IOException { return WorkspaceFiles.sha256(file); }
}
