import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// 版本号：小版本号（patch）在每次 release 构建时自动 +1，并持久化到 version.properties
// ---------------------------------------------------------------------------
val versionFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}
var vMajor = versionProps.getProperty("major", "0").toIntOrNull() ?: 0
var vMinor = versionProps.getProperty("minor", "0").toIntOrNull() ?: 0
var vPatch = versionProps.getProperty("patch", "1").toIntOrNull() ?: 1

val requestedTaskNames = gradle.startParameter.taskNames
    .map { it.substringAfterLast(':').lowercase(Locale.ROOT) }
val buildsRelease = requestedTaskNames.any { task ->
    task.contains("release") || task == "build" || task == "assemble" || task == "bundle"
}

if (buildsRelease) {
    vPatch += 1
    versionProps.setProperty("major", vMajor.toString())
    versionProps.setProperty("minor", vMinor.toString())
    versionProps.setProperty("patch", vPatch.toString())
    versionFile.outputStream().use {
        versionProps.store(it, "Auto-incremented on each release build")
    }
}

val appVersionName = "$vMajor.$vMinor.$vPatch"
val appVersionCode = vMajor * 1_000_000 + vMinor * 10_000 + vPatch
val buildTimestamp =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

android {
    namespace = "com.captainivy.ebookreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.captainivy.ebookreader"
        minSdk = 21
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // 构建时间戳，供设置页展示
        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")
    }

    buildTypes {
        debug {
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1")
    implementation("org.apache.commons:commons-compress:1.26.2")

    testImplementation("junit:junit:4.13.2")
}
