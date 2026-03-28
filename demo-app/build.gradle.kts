import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
}

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

val openAiApiKey = (System.getenv("OPENAI_API_KEY") ?: "").escapeForBuildConfig()
val openAiWizardModel = (System.getenv("OPENAI_WIZARD_MODEL")
    ?: "gpt-5.4-nano").escapeForBuildConfig()
val openAiBaseUrl = (System.getenv("OPENAI_BASE_URL")
    ?: "https://api.openai.com/v1").escapeForBuildConfig()
val openAiOrganization = (System.getenv("OPENAI_ORGANIZATION") ?: "").escapeForBuildConfig()

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "app.voicenote.demo"
    compileSdk = 35

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
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
