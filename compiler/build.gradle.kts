/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
  packageName("dev.zacsweers.lattice.compiler")
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
  testImplementation(libs.kotlin.reflect)
  testImplementation(libs.kotlin.stdlib)
  testImplementation(libs.kotlin.compilerEmbeddable)
  // Cover for https://github.com/tschuchortdev/kotlin-compile-testing/issues/274
  testImplementation(libs.kotlin.aptEmbeddable)
  testImplementation(libs.kotlinCompileTesting)
  testImplementation(libs.okio)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.truth)
}
