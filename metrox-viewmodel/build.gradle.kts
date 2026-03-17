// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("metro.base")
  id("metro.publish")
}

metroProject { configureCommonKmpTargets("metrox-viewmodel") }

kotlin {
  sourceSets {
    commonMain {
      dependencies {
        api(project(":runtime"))
        api(libs.kotlin.stdlib.published)
        api(libs.jetbrains.lifecycle.viewmodel)
      }
    }
    commonTest { dependencies { implementation(libs.kotlin.test) } }
    webMain {
      dependencies {
        // https://youtrack.jetbrains.com/issue/KT-84582
        api(libs.kotlin.stdlib)
      }
    }
  }

  targets.configureEach {
    val target = this
    compilations.configureEach {
      compileTaskProvider.configure {
        if (target.platformType == KotlinPlatformType.js) {
          compilerOptions.freeCompilerArgs.add(
            // These are all read at compile-time
            "-Xwarning-level=RUNTIME_ANNOTATION_NOT_SUPPORTED:disabled"
          )
        }
      }
    }
  }
}
