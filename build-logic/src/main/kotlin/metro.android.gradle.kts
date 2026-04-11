// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
val jvmTargetVersion = catalog.findVersion("jvmTarget").get().requiredVersion

// Configure common Android settings
fun CommonExtension<*, *, *, *, *, *>.configureCommonAndroid() {
  compileSdk = 36

  defaultConfig { minSdk = 28 }

  compileOptions {
    val javaVersion = JavaVersion.toVersion(jvmTargetVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
  }
}

// Android Library configuration
pluginManager.withPlugin("com.android.library") {
  extensions.configure<LibraryExtension> { configureCommonAndroid() }
  configure<LibraryAndroidComponentsExtension> {
    beforeVariants { variant ->
      // Single-variant libraries
      variant.enable = variant.buildType != "debug"
    }
  }
}

// Android Application configuration
pluginManager.withPlugin("com.android.application") {
  extensions.configure<ApplicationExtension> {
    configureCommonAndroid()
    defaultConfig { targetSdk = 36 }
    buildTypes { maybeCreate("debug").apply { matchingFallbacks += listOf("release") } }
  }
}
