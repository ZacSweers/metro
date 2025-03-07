// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dokka)
  alias(libs.plugins.mavenPublish)
  alias(libs.plugins.ksp)
  alias(libs.plugins.poko)
  alias(libs.plugins.buildConfig)
}

kotlin {
  compilerOptions {
    optIn.addAll(
      "dev.drewhamilton.poko.SkipSupport",
      "kotlin.contracts.ExperimentalContracts",
      "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
      "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
    )
  }
}

buildConfig {
  generateAtSync = true
  packageName("dev.zacsweers.metro.compiler")
  kotlin {
    useKotlinOutput {
      internalVisibility = true
      topLevelConstants = true
    }
  }
  sourceSets.named("main") {
    buildConfigField("String", "PLUGIN_ID", libs.versions.pluginId.map { "\"$it\"" })
  }
  sourceSets.named("test") {
    buildConfigField("String", "JVM_TARGET", libs.versions.jvmTarget.map { "\"$it\"" })
  }
}

tasks.test { maxParallelForks = Runtime.getRuntime().availableProcessors() * 2 }

dependencies {
  compileOnly(libs.kotlin.compilerEmbeddable)
  compileOnly(libs.kotlin.stdlib)
  implementation(libs.autoService)
  implementation(libs.picnic)
  ksp(libs.autoService.ksp)

  testImplementation(project(":runtime"))
  testImplementation(project(":interop-dagger"))
  testImplementation(libs.kotlin.reflect)
  testImplementation(libs.kotlin.stdlib)
  testImplementation(libs.kotlin.compilerEmbeddable)
  // Cover for https://github.com/tschuchortdev/kotlin-compile-testing/issues/274
  testImplementation(libs.kotlin.aptEmbeddable)
  testImplementation(libs.kct)
  testImplementation(libs.kct.ksp)
  testImplementation(libs.okio)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.truth)
  testImplementation(libs.coroutines)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.dagger.compiler)
  testImplementation(libs.dagger.runtime)
}
