// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import dev.drewhamilton.poko.gradle.PokoFirIdeMode
import org.gradle.api.artifacts.ExternalDependency

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.plugin.serialization)
  alias(libs.plugins.poko)
  id("metro.publish")
}

poko {
  firIdeMode.set(PokoFirIdeMode.NONE)
}

project.afterEvaluate {
  configurations.named("implementation") {
    dependencies.removeIf { it is ExternalDependency && it.group == "dev.drewhamilton.poko" }
  }
}

dependencies {
  compileOnly(libs.kotlin.compiler)
  compileOnly(libs.kotlin.stdlib)
  compileOnly(libs.poko.annotations)

  implementation(libs.kotlinx.serialization.json)
}
