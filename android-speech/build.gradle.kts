import java.util.Properties

plugins {
    kotlin("jvm") version "2.0.21"
}

fun resolveAndroidSdkRoot(): String {
    val envSdkRoot = System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
    if (!envSdkRoot.isNullOrBlank()) {
        return envSdkRoot
    }

    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        val properties = Properties()
        localPropertiesFile.inputStream().use(properties::load)
        val sdkDir = properties.getProperty("sdk.dir")?.trim()
        if (!sdkDir.isNullOrEmpty()) {
            return sdkDir
        }
    }

    error(
        "ANDROID_SDK_ROOT or ANDROID_HOME is required to compile the android-speech module, or sdk.dir must be set in ${rootProject.file("local.properties")}.",
    )
}

val androidSdkRoot = resolveAndroidSdkRoot()
val androidJar = file("$androidSdkRoot/platforms/android-35/android.jar")

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    compileOnly(files(androidJar))

    testImplementation(kotlin("test"))
    testImplementation(files(androidJar))
}

kotlin {
    jvmToolchain(21)
}
