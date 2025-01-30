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
import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.validation.ExperimentalBCVApi
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
  dependencies {
    // Include our included build
    classpath("dev.zacsweers.metro:gradle-plugin")
  }
}

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.dokka)
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.mavenPublish) apply false
  alias(libs.plugins.atomicfu) apply false
  alias(libs.plugins.spotless)
  alias(libs.plugins.binaryCompatibilityValidator)
  alias(libs.plugins.poko) apply false
}

apiValidation {
  ignoredProjects += listOf("compiler", "integration-tests")
  ignoredPackages += listOf("dev.zacsweers.metro.internal")
  @OptIn(ExperimentalBCVApi::class)
  klib {
    // This is only really possible to run on macOS
    //    strictValidation = true
    enabled = true
  }
}

dokka {
  dokkaPublications.html {
    outputDirectory.set(rootDir.resolve("docs/api/0.x"))
    includes.from(project.layout.projectDirectory.file("README.md"))
  }
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
        rootProject.file("spotless/spotless.kt"),
        "(import|plugins|buildscript|dependencies|pluginManagement|dependencyResolutionManagement)",
      )
    }
    // Apply license formatting separately for kotlin files so we can prevent it from overwriting
    // copied files
    format("license") {
      licenseHeaderFile(rootProject.file("spotless/spotless.kt"), "(package|@file:)")
      target("src/**/*.kt")
      targetExclude(
        "**/AbstractMapFactory.kt",
        "**/Assisted.kt",
        "**/AssistedFactory.kt",
        "**/ClassKey.kt",
        "**/DelegateFactory.kt",
        "**/DoubleCheck.kt",
        "**/DoubleCheckTest.kt",
        "**/ElementsIntoSet.kt",
        "**/InstanceFactory.kt",
        "**/InstanceFactoryTest.kt",
        "**/IntKey.kt",
        "**/IntoMap.kt",
        "**/IntoSet.kt",
        "**/LongKey.kt",
        "**/MapFactory.kt",
        "**/MapKey.kt",
        "**/MapProviderFactory.kt",
        "**/MapProviderFactoryTest.kt",
        "**/MembersInjector.kt",
        "**/Multibinds.kt",
        "**/NameAllocator.kt",
        "**/NameAllocatorTest.kt",
        "**/ProviderOfLazy.kt",
        "**/SetFactory.kt",
        "**/SetFactoryTest.kt",
        "**/StringKey.kt",
        "**/cycles/CyclesTest.kt",
        "**/cycles/LongCycle.kt",
      )
    }
  }
}

subprojects {
  group = project.property("GROUP") as String
  version = project.property("VERSION_NAME") as String

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
    if ("sample" !in project.path) {
      configure<KotlinProjectExtension> { explicitApi() }
    }
  }

  pluginManager.withPlugin("org.jetbrains.dokka") {
    configure<DokkaExtension> {
      basePublicationsDirectory.set(layout.buildDirectory.dir("dokkaDir"))
      dokkaSourceSets.configureEach {
        skipDeprecated.set(true)
        documentedVisibilities.add(VisibilityModifier.Public)
        sourceLink {
          localDirectory.set(layout.projectDirectory.dir("src"))
          val relPath = rootProject.projectDir.toPath().relativize(projectDir.toPath())
          remoteUrl(
            providers.gradleProperty("POM_SCM_URL").map { scmUrl ->
              "$scmUrl/tree/main/$relPath/src"
            }
          )
          remoteLineSuffix.set("#L")
        }
      }
    }
  }

  plugins.withId("com.vanniktech.maven.publish") {
    configure<MavenPublishBaseExtension> { publishToMavenCentral(automaticRelease = true) }

    // configuration required to produce unique META-INF/*.kotlin_module file names
    tasks.withType<KotlinCompile>().configureEach {
      compilerOptions { moduleName.set(project.property("POM_ARTIFACT_ID") as String) }
    }
  }
}

dependencies { dokka(project(":runtime")) }
