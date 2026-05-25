// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidHostTestCompilation
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
val jvmTargetVersion = catalog.findVersion("jvmTarget").get().requiredVersion
val compileSdkVersion = catalog.findVersion("android-compileSdk").get().requiredVersion.toInt()

pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
  pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    (kotlinExtension as KotlinMultiplatformExtension)
      .targets
      .withType(KotlinMultiplatformAndroidLibraryTarget::class.java)
      .configureEach {
        compileSdk = compileSdkVersion
        minSdk = 28
        compilations.withType(KotlinMultiplatformAndroidHostTestCompilation::class.java) {
          targetSdk { release(compileSdkVersion) }
        }
      }
  }
}

// Configure common Android settings
fun CommonExtension.configureCommonAndroid() {
  compileSdk = compileSdkVersion
}

// Android Library configuration
pluginManager.withPlugin("com.android.library") {
  extensions.configure<LibraryExtension> {
    configureCommonAndroid()
    defaultConfig { minSdk = 28 }

    compileOptions {
      sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
      targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    }
  }
}

// Android Application configuration
pluginManager.withPlugin("com.android.application") {
  extensions.configure<ApplicationExtension> {
    configureCommonAndroid()
    defaultConfig {
      minSdk = 28
      targetSdk = 36
    }

    compileOptions {
      sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
      targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    }

    buildTypes { maybeCreate("debug").apply { matchingFallbacks += listOf("release") } }
  }
}
