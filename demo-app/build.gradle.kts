import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
}

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun configuredBuildConfigValue(
    name: String,
    defaultValue: String = "",
): String {
    val configuredValue = sequenceOf(
        providers.gradleProperty(name).orNull,
        localProperties.getProperty(name),
        System.getenv(name),
    ).mapNotNull { value -> value?.trim() }
        .firstOrNull { value -> value.isNotEmpty() }
        ?: defaultValue
    return configuredValue.escapeForBuildConfig()
}

val openAiApiKey = configuredBuildConfigValue("OPENAI_API_KEY")
val openAiWizardModel = configuredBuildConfigValue("OPENAI_WIZARD_MODEL", "gpt-5.4-nano")
val openAiBaseUrl = configuredBuildConfigValue("OPENAI_BASE_URL", "https://api.openai.com/v1")
val openAiOrganization = configuredBuildConfigValue("OPENAI_ORGANIZATION")
val jobTreadPaveUrl = configuredBuildConfigValue("JOBTREAD_PAVE_URL")
val jobTreadGrantKey = configuredBuildConfigValue("JOBTREAD_GRANT_KEY")

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "app.voicenote.demo"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.voicenote.demo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        buildConfigField("String", "OPENAI_WIZARD_MODEL", "\"$openAiWizardModel\"")
        buildConfigField("String", "OPENAI_BASE_URL", "\"$openAiBaseUrl\"")
        buildConfigField("String", "OPENAI_ORGANIZATION", "\"$openAiOrganization\"")
        buildConfigField("String", "JOBTREAD_PAVE_URL", "\"$jobTreadPaveUrl\"")
        buildConfigField("String", "JOBTREAD_GRANT_KEY", "\"$jobTreadGrantKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":"))
    implementation(project(":android-tts"))
    implementation(project(":android-speech"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
