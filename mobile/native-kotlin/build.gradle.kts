plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
dependencies {
    api(project(":native-compiler"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.24")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
