/*
 * Codex for Android — app module.
 *
 * 64-bit only application (arm64-v8a + x86_64). The native part is the PTY
 * implementation ("libtermux") vendored from AndroidIDE/Termux.
 */

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

val codexVersionName: String = (findProperty("codex.versionName") as String?) ?: "1.0.0"
val codexVersionCode: Int = ((findProperty("codex.versionCode") as String?) ?: "1").toInt()

android {
  namespace = "ai.codex.android"
  compileSdk = 34
  ndkVersion = "26.1.10909125"

  defaultConfig {
    applicationId = "ai.codex.android"
    minSdk = 26
    targetSdk = 34
    versionCode = codexVersionCode
    versionName = codexVersionName

    // 64-bit ABIs only.
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }

    externalNativeBuild {
      ndkBuild {
        cFlags += arrayOf(
          "-std=c11", "-Wall", "-Wextra", "-Werror", "-Os",
          "-fno-stack-protector", "-Wl,--gc-sections"
        )
      }
    }
  }

  externalNativeBuild {
    ndkBuild {
      path = file("src/main/cpp/Android.mk")
    }
  }

  buildFeatures {
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  packaging {
    jniLibs.useLegacyPackaging = true
    resources.excludes += setOf(
      "META-INF/*.kotlin_module",
      "META-INF/DEPENDENCIES",
      "META-INF/LICENSE*"
    )
  }

  buildTypes {
    debug {
      isMinifyEnabled = false
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      // Signed with the debug key when no release keystore is configured, so that
      // `assembleRelease` always produces an installable APK.
      signingConfig = signingConfigs.getByName("debug")
    }
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.00"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-graphics")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.activity:activity-ktx:1.9.2")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.annotation:annotation:1.8.2")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
  implementation("androidx.webkit:webkit:1.11.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  debugImplementation("androidx.compose.ui:ui-tooling")

  testImplementation("junit:junit:4.13.2")
}
