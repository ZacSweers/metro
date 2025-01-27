/*
 * Copyright (C) 2025 Zac Sweers
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
import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.kapt) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.atomicfu) apply false
  id("dev.zacsweers.metro") apply false
  alias(libs.plugins.spotless)
  alias(libs.plugins.mavenPublish) apply false // wat
}

allprojects {
  apply(plugin = "com.diffplug.spotless")
  configure<SpotlessExtension> {
    format("misc") {
      target("*.gradle", "*.md", ".gitignore")
      trimTrailingWhitespace()
      leadingTabsToSpaces(2)
      endWithNewline()
    }
    kotlin {
      target("src/**/*.kt")
      ktfmt(libs.versions.ktfmt.get()).googleStyle().configure { it.setRemoveUnusedImports(true) }
      trimTrailingWhitespace()
      endWithNewline()
      targetExclude("**/spotless.kt")
    }
    kotlinGradle {
      target("*.kts")
      ktfmt(libs.versions.ktfmt.get()).googleStyle().configure { it.setRemoveUnusedImports(true) }
      trimTrailingWhitespace()
      endWithNewline()
      licenseHeaderFile(
        rootProject.file("../spotless/spotless.kt"),
        "(import|plugins|buildscript|dependencies|pluginManagement|dependencyResolutionManagement)",
      )
    }
    // Apply license formatting separately for kotlin files so we can prevent it from overwriting
    // copied files
    format("license") {
      licenseHeaderFile(rootProject.file("../spotless/spotless.kt"), "(package|@file:)")
      target("src/**/*.kt")
    }
  }
}

subprojects {
  pluginManager.withPlugin("java") {
    configure<JavaPluginExtension> {
      toolchain { languageVersion.set(libs.versions.jdk.map(JavaLanguageVersion::of)) }
    }
    tasks.withType<JavaCompile>().configureEach {
      options.release.set(libs.versions.jvmTarget.map(String::toInt))
    }
  }

  plugins.withType<KotlinBasePlugin> {
    project.tasks.withType<KotlinCompilationTask<*>>().configureEach {
      compilerOptions {
        progressiveMode.set(true)
        if (this is KotlinJvmCompilerOptions) {
          jvmTarget.set(libs.versions.jvmTarget.map(JvmTarget::fromTarget))
          freeCompilerArgs.addAll("-Xjvm-default=all")
        }
      }
    }
  }
}
