package dev.forge.build;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.JSONObject;
import org.json.JSONArray;

/** Creates a standalone native Java/XML project without scripts or downloaded dependencies. */
public final class AndroidProjectTemplate {
    private AndroidProjectTemplate() { }
    public static void create(File root, String displayName, String applicationId) throws IOException {
        if (displayName == null || displayName.trim().isEmpty() || displayName.length() > 100 || displayName.chars().anyMatch(c -> c < 32))
            throw new IOException("App name must contain 1–100 printable characters");
        if (applicationId == null || !applicationId.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) throw new IOException("Invalid application ID");
        if (root.exists()) throw new IOException("Template destination already exists; existing files are never replaced");
        Files.createDirectories(root.toPath().toAbsolutePath().getParent());
        Files.createDirectory(root.toPath());
        JSONObject config = new JSONObject().put("schemaVersion", 1).put("applicationId", applicationId).put("namespace", applicationId)
            .put("compileSdk", 29).put("minSdk", 28).put("targetSdk", 29).put("versionCode", 1).put("versionName", "1.0")
            .put("manifest", "AndroidManifest.xml").put("java", new JSONArray().put("src"))
            .put("resources", new JSONArray().put("res")).put("assets", new JSONArray().put("assets"));
        write(root, ".forge/project.json", config.toString(2) + "\n");
        write(root, "AndroidManifest.xml", "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"" + applicationId + "\">\n" +
            "  <application android:label=\"@string/app_name\" android:theme=\"@android:style/Theme.Material.Light.NoActionBar\">\n" +
            "    <activity android:name=\".MainActivity\" android:exported=\"true\">\n" +
            "      <intent-filter><action android:name=\"android.intent.action.MAIN\"/><category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter>\n" +
            "    </activity>\n  </application>\n</manifest>\n");
        String label = "\"" + displayName.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        write(root, "res/values/strings.xml", "<resources><string name=\"app_name\" formatted=\"false\">" + xml(label) +
            "</string><string name=\"welcome\">Built on your phone with Forge</string><string name=\"tap\">Tap me</string></resources>\n");
        write(root, "res/layout/main.xml", "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:orientation=\"vertical\" android:gravity=\"center\" android:padding=\"24dp\">\n" +
            "  <TextView android:id=\"@+id/message\" android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"@string/welcome\" android:textSize=\"22sp\"/>\n" +
            "  <Button android:id=\"@+id/tap\" android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"@string/tap\"/>\n</LinearLayout>\n");
        write(root, "src/" + applicationId.replace('.', '/') + "/MainActivity.java", "package " + applicationId + ";\n\n" +
            "public class MainActivity extends android.app.Activity {\n    private int taps;\n" +
            "    @Override public void onCreate(android.os.Bundle state) {\n        super.onCreate(state);\n        setContentView(R.layout.main);\n" +
            "        if (state != null) taps = state.getInt(\"taps\");\n        updateMessage();\n" +
            "        findViewById(R.id.tap).setOnClickListener(new android.view.View.OnClickListener() {\n" +
            "            @Override public void onClick(android.view.View view) { taps++; updateMessage(); }\n        });\n    }\n" +
            "    private void updateMessage() {\n        ((android.widget.TextView) findViewById(R.id.message)).setText(taps == 0 ? getString(R.string.welcome) : \"Taps: \" + taps);\n    }\n" +
            "    @Override protected void onSaveInstanceState(android.os.Bundle state) { state.putInt(\"taps\", taps); super.onSaveInstanceState(state); }\n}\n");
        write(root, "README.md", "# " + displayName.trim() + "\n\nOpen the project in Forge, edit Java/XML files, then use Build and run.\n" +
            "The project uses the bundled Android native compiler and needs no Gradle wrapper or Termux.\n" +
            "Configuration: `.forge/project.json`. Java 8, SDK 29, minimum Android 9.\n");
        write(root, ".gitignore", "build/\n.forge/local/\n");
        new ProjectInspector().inspect(root);
    }
    private static String xml(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static void write(File root, String path, String text) throws IOException {
        File file = WorkspaceFiles.resolve(root, path); Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
}
