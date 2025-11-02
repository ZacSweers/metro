// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.spotless.LineEnding
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.kapt) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.plugin.compose) apply false
  alias(libs.plugins.ksp) apply false
  id("dev.zacsweers.metro") apply false
  alias(libs.plugins.spotless)
  alias(libs.plugins.mavenPublish) apply false // wat
  alias(libs.plugins.compose) apply false
  alias(libs.plugins.kotlin.plugin.serialization) apply false
}

val ktfmtVersion = libs.versions.ktfmt.get()

allprojects {
  apply(plugin = "com.diffplug.spotless")
  configure<SpotlessExtension> {
    setLineEndings(LineEnding.GIT_ATTRIBUTES_FAST_ALLSAME)
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
          freeCompilerArgs.addAll(
            "-jvm-default=no-compatibility",
            // Big yikes in how this was rolled out as noisy compiler warnings
            "-Xannotation-default-target=param-property",
          )
        }
      }
    }
  }
}

plugins.withType<YarnPlugin> {
  the<YarnRootEnvSpec>().apply {
    version = "1.22.22"
    yarnLockAutoReplace = true
    installationDirectory = projectDir
    ignoreScripts = false
  }
}

plugins.withType<NodeJsRootPlugin> { the<NodeJsEnvSpec>().apply { this.version = "24.4.1" } }
