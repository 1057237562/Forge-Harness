plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

val forgeAndroidXml = Attribute.of("dev.forge.android-xml", Boolean::class.javaObjectType)
subprojects {
    dependencies {
        artifactTypes.configureEach { if (name == "jar") attributes.attribute(forgeAndroidXml, false) }
        registerTransform(dev.forge.gradle.AndroidXmlTransform::class) {
            from.attribute(forgeAndroidXml, false)
            to.attribute(forgeAndroidXml, true)
        }
    }
    configurations.configureEach { attributes.attribute(forgeAndroidXml, true) }
}
