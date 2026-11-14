import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.tvremote.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tvremote.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Kept deliberately small: the Chromecast has little RAM. Plain Views, no Compose.
dependencies {
    implementation(project(":protocol"))
    // Reaches video players inside cross-origin iframes, which plain evaluateJavascript cannot.
    implementation("androidx.webkit:webkit:1.12.1")
}
