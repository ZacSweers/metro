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
  artifactId.set("graphs")
  name.set("Metro Graphs")
}

metroProject {
  jvmTarget.set(libs.versions.compilerJvmTarget)
  languageVersion.set(KotlinVersion.KOTLIN_2_2)
  apiVersion.set(KotlinVersion.KOTLIN_2_2)
}

kotlin {
  jvm()
  js {
    outputModuleName.set("metro-graphs")
    useEsModules()
    browser()
    nodejs()
  }

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlin.stdlib.published)
      api(libs.kotlinx.serialization.json)
    }
    jsMain.dependencies {
      // https://youtrack.jetbrains.com/issue/KT-84582
      api(libs.kotlin.stdlib)
    }
  }
}
