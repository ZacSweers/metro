// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.plugin.serialization)
  id("metro.base")
  id("metro.publish")
}

metroArtifact {
  artifactId.set("graph-viewer")
  name.set("Metro Graph Viewer")
}

metroProject {
  jvmTarget.set(libs.versions.compilerJvmTarget)
  languageVersion.set(KotlinVersion.KOTLIN_2_2)
  apiVersion.set(KotlinVersion.KOTLIN_2_2)
}

kotlin {
  compilerOptions.optIn.add("dev.zacsweers.metro.graph.ExperimentalMetroGraphApi")
  jvm()
  js {
    outputModuleName.set("metro-graph-viewer")
    useEsModules()
    browser()
    nodejs { testTask { useMocha { timeout = "30s" } } }
    binaries.library()
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":graphs"))
      api(libs.kotlin.stdlib.published)
      api(libs.kotlinx.serialization.json)
    }
    jsMain.dependencies {
      // https://youtrack.jetbrains.com/issue/KT-84582
      api(libs.kotlin.stdlib)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}
