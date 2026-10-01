package dev.forge.build;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

public class ProjectInspectorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File project;
    private static final String SCRIPT = "plugins { id 'com.android.application' }\nandroid {\n namespace 'dev.forge.test'\n compileSdk 29\n defaultConfig {\n applicationId 'dev.forge.test'\n minSdk 28\n targetSdk 29\n versionCode 2\n versionName '1.1'\n }\n compileOptions {\n sourceCompatibility JavaVersion.VERSION_1_8\n targetCompatibility JavaVersion.VERSION_1_8\n }\n}\n";
    @Before public void setup() throws Exception {
        project = temporary.newFolder("project");
        write("app/src/main/AndroidManifest.xml", "<manifest xmlns:android='http://schemas.android.com/apk/res/android'><application/></manifest>");
        write("app/src/main/java/dev/forge/test/Example.java", "package dev.forge.test; public class Example {}\n");
        write("app/build.gradle", SCRIPT);
        write("settings.gradle", "rootProject.name = 'Example'\ninclude ':app'\n");
    }
    @Test public void importsStaticGroovyAndScopes() throws Exception {
        write("app/build.gradle", SCRIPT + "dependencies {\n implementation 'g:a:1.0'\n compileOnly('g:b:2.0')\n runtimeOnly 'g:c:3.0'\n}\n");
        ProjectModel model = inspect();
        assertEquals("dev.forge.test", model.applicationId);
        assertEquals(29, model.compileSdk);
        assertEquals(2, model.versionCode);
        assertEquals(ProjectModel.Scope.COMPILE_ONLY, model.dependencies.get(1).scope);
        assertEquals(ProjectModel.Scope.RUNTIME_ONLY, model.dependencies.get(2).scope);
    }
    @Test public void handlesBracesInCommentsAndQuotedStrings() throws Exception {
        write("app/build.gradle", SCRIPT.replace("versionName '1.1'", "versionName 'build { }' // } ignored\n/* { ignored */"));
        assertEquals("build { }", inspect().versionName);
    }
    @Test public void rejectsDynamicSdkWithLine() throws Exception {
        write("app/build.gradle", SCRIPT.replace("compileSdk 29", "compileSdk rootProject.ext.sdk"));
        CompatibilityException error = unsupported("constant integer");
        assertEquals(4, error.line);
        assertEquals("app/build.gradle", error.file);
    }
    @Test public void rejectsInterpolatedDependency() throws Exception {
        write("app/build.gradle", SCRIPT + "dependencies { implementation \"g:a:$version\" }\n");
        unsupported("Interpolated");
    }
    @Test public void rejectsCustomPlugin() throws Exception {
        write("app/build.gradle", SCRIPT.replace("com.android.application", "org.jetbrains.kotlin.android"));
        unsupported("Only the Android application plugin");
    }
    @Test public void rejectsCustomRootLogicEvenWithoutSettingsFile() throws Exception {
        Files.delete(new File(project, "settings.gradle").toPath());
        write("build.gradle", "allprojects { android { compileSdk 35 } }\n");
        unsupported("Root build logic");
    }
    @Test public void rejectsExtraModules() throws Exception {
        write("settings.gradle", "include ':app', ':library'\n");
        unsupported("Multiple or dynamic modules");
    }
    @Test public void rejectsKotlinOutsideJavaDirectory() throws Exception {
        write("app/src/main/kotlin/Test.kt", "class Test");
        unsupported("src/main/kotlin");
    }
    @Test public void rejectsDebugSourceSetRatherThanOmittingIt() throws Exception {
        write("app/src/debug/java/Test.java", "class Test {}");
        unsupported("Source set 'debug'");
    }
    @Test public void rejectsBuildConfigAndManifestPlaceholders() throws Exception {
        write("app/build.gradle", SCRIPT.replace("versionCode 2", "manifestPlaceholders = [name: 'value']"));
        unsupported("manifestPlaceholders");
    }
    @Test public void rejectsDtdBeforeXmlParsing() throws Exception {
        write("app/src/main/AndroidManifest.xml", "<!DOCTYPE manifest [<!ENTITY data SYSTEM 'file:///etc/passwd'>]><manifest/>");
        unsupported("DTD");
    }
    @Test public void explicitConfigOverridesUnsupportedGradleWithNotice() throws Exception {
        write("app/build.gradle", "thisWouldExecuteArbitraryCode()\n");
        write(".forge/project.json", explicit(""));
        ProjectModel model = inspect();
        assertEquals(".forge/project.json", model.configuration);
        assertTrue(model.notices.get(0).contains("authoritative"));
    }
    @Test public void explicitConfigRejectsUnknownKeys() throws Exception {
        write(".forge/project.json", explicit(",\"minifyEnabld\":true"));
        unsupported("Unknown configuration key");
    }
    @Test public void explicitConfigRejectsNonIntegralSdk() throws Exception {
        write(".forge/project.json", explicit("").replace("\"compileSdk\":29", "\"compileSdk\":29.5"));
        unsupported("must be an integer");
    }
    @Test public void explicitConfigRejectsPathEscape() throws Exception {
        write(".forge/project.json", explicit("").replace("app/src/main/java", "../outside"));
        try { inspect(); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("escapes")); }
    }
    @Test public void rejectsDynamicMavenVersions() throws Exception {
        write("app/build.gradle", SCRIPT + "dependencies { implementation 'g:a:1.+' }\n");
        unsupported("fixed g:a:v");
    }
    @Test public void rejectsDisabledApplicationPlugin() throws Exception {
        write("app/build.gradle", SCRIPT.replace("id 'com.android.application'", "id 'com.android.application' apply false"));
        unsupported("must be applied");
    }
    @Test public void rejectsQuotedDeclarationNames() throws Exception {
        write("app/build.gradle", SCRIPT.replace("compileSdk 29", "'compileSdk' 29"));
        unsupported("not a string expression");
    }
    @Test public void rejectsJetifierRatherThanProducingUntransformedLibraries() throws Exception {
        write("gradle.properties", "android.enableJetifier=true\n");
        unsupported("cannot be silently ignored");
    }
    @Test public void acceptsIrrelevantGradleJvmOptionsWithoutExecutingThem() throws Exception {
        write("gradle.properties", "org.gradle.jvmargs=-Xmx1g\nandroid.useAndroidX=true\n");
        assertEquals(29, inspect().compileSdk);
    }
    private String explicit(String extra) {
        return "{\"schemaVersion\":1,\"applicationId\":\"dev.forge.test\",\"compileSdk\":29,\"minSdk\":28,\"targetSdk\":29,\"manifest\":\"app/src/main/AndroidManifest.xml\",\"java\":[\"app/src/main/java\"],\"resources\":[\"app/src/main/res\"]" + extra + "}";
    }
    private ProjectModel inspect() throws IOException { return new ProjectInspector().inspect(project); }
    private CompatibilityException unsupported(String text) throws Exception {
        try { inspect(); fail("Expected unsupported configuration: " + text); }
        catch (CompatibilityException error) { assertTrue(error.toString(), error.getMessage().contains(text)); return error; }
        throw new AssertionError();
    }
    private void write(String path, String text) throws IOException {
        File file = new File(project, path); file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}
