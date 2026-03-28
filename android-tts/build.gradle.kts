plugins {
    kotlin("jvm") version "2.0.21"
}

val androidSdkRoot = System.getenv("ANDROID_SDK_ROOT")
    ?: System.getenv("ANDROID_HOME")
    ?: error("ANDROID_SDK_ROOT or ANDROID_HOME is required to compile the android-tts module.")
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
