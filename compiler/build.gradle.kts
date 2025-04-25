// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dokka)
  alias(libs.plugins.mavenPublish)
  alias(libs.plugins.poko)
  alias(libs.plugins.buildConfig)
  alias(libs.plugins.wire)
  alias(libs.plugins.shadow)
  alias(libs.plugins.testkit)
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
    buildConfigField(
      "String",
      "METRO_VERSION",
      providers.gradleProperty("VERSION_NAME").map { "\"$it\"" },
    )
    buildConfigField("String", "PLUGIN_ID", libs.versions.pluginId.map { "\"$it\"" })
  }
  sourceSets.named("test") {
    buildConfigField("String", "JVM_TARGET", libs.versions.jvmTarget.map { "\"$it\"" })
  }
}

tasks.test {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
  systemProperty("metro.buildDir", project.layout.buildDirectory.asFile.get().absolutePath)
}

wire { kotlin { javaInterop = false } }

val isPublishing = providers.environmentVariable("PUBLISHING").isPresent

val shadowJar =
  tasks.shadowJar.apply {
    configure {
      if (isPublishing) {
        // Since we change the classifier of the shadowJar we need to disable the default jar task
        // or we'll get two artifacts that have the same classifier
        archiveClassifier.set("ignored")
      }
      relocate("com.squareup.wire", "dev.zacsweers.metro.compiler.shaded.com.squareup.wire")
      relocate("com.squareup.okio", "dev.zacsweers.metro.compiler.shaded.com.squareup.okio")
      relocate(
        "com.jakewharton.picnic",
        "dev.zacsweers.metro.compiler.shaded.com.jakewharton.picnic",
      )
      relocate(
        "com.jakewharton.crossword",
        "dev.zacsweers.metro.compiler.shaded.com.jakewharton.crossword",
      )
    }
  }

artifacts {
  runtimeOnly(shadowJar)
  archives(shadowJar)
}

dependencies {
  compileOnly(libs.kotlin.compilerEmbeddable)
  compileOnly(libs.kotlin.stdlib)
  implementation(libs.autoService)
  implementation(libs.picnic) { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib") }
  shadow(libs.wire.runtime) { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib") }

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
  testImplementation(libs.anvil.annotations)
}
