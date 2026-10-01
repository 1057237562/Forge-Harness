import java.util.Properties
plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val sdk = local.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: "missing-sdk"
dependencies {
    api(project(":native-build-core"))
    implementation("org.eclipse.jdt:ecj:3.18.0")
    implementation("org.ow2.asm:asm:9.8")
    implementation("com.android.tools.build:manifest-merger:30.0.3") {
        // The JAXB chain also ships these javax.activation classes in com.sun.activation.
        exclude(group = "jakarta.activation", module = "jakarta.activation-api")
    }
    implementation("com.android.tools:common:30.0.3")
    implementation("xerces:xercesImpl:2.12.2") { exclude(group = "xml-apis", module = "xml-apis") }
    implementation(files("$sdk/build-tools/30.0.3/lib/d8.jar", "$sdk/build-tools/30.0.3/lib/apksigner.jar"))
    testImplementation("org.json:json:20250107")
    testImplementation("junit:junit:4.13.2")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
