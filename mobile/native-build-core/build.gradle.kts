plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
dependencies {
    compileOnly("org.json:json:20250107")
    testImplementation("org.json:json:20250107")
    testImplementation("junit:junit:4.13.2")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
