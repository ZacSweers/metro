// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins { alias(libs.plugins.android.application) }

val runtimeTracingEnabled =
  providers.gradleProperty("metro.benchmark.runtimeTracing").map(String::toBoolean).getOrElse(false)

android {
  namespace = "dev.zacsweers.metro.benchmark.startup.android"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.zacsweers.metro.benchmark.startup.android"
    minSdk = 28
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    buildConfigField("boolean", "METRO_RUNTIME_TRACING", runtimeTracingEnabled.toString())
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures { buildConfig = true }
}

dependencies {
  implementation(libs.androidx.core)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.androidx.tracing.wire)

  // Depend on the generated app component
  implementation(project(":app:component"))
}
