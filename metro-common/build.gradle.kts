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

metroArtifact {
  artifactId.set("metro-common")
  name.set("Metro Common")
}

poko {
  firIdeMode.set(PokoFirIdeMode.NONE)
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xreturn-value-checker=full", "-Xcontext-parameters")
    optIn.add("kotlin.contracts.ExperimentalContracts")
  }
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
  compileOnly(libs.androidx.collection)
  compileOnly(libs.androidx.tracing)

  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.compiler)
  testImplementation(libs.androidx.collection)
  testImplementation(libs.androidx.tracing)
  testImplementation(libs.poko.annotations)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.truth)
}
