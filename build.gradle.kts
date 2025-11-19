// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessExtensionPredeclare
import com.diffplug.spotless.LineEnding
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.validation.ExperimentalBCVApi
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
  alias(libs.plugins.wire) apply false
}

apiValidation {
  ignoredProjects += buildList {
    add("compiler")
    add("compiler-tests")
    add("compiler-compat")
    layout.projectDirectory.dir("compiler-compat").asFile.listFiles()!!.forEach {
      if (it.isDirectory && it.name.startsWith("k")) {
        add(it.name)
      }
    }
  }
  ignoredPackages +=
    listOf(
      "dev.zacsweers.metro.internal",
      "dev.zacsweers.metro.compiler.compat",
      "dev.zacsweers.metro.interop.dagger.internal",
    )
  @OptIn(ExperimentalBCVApi::class)
  klib {
    // This is only really possible to run on macOS
    // strictValidation = true
    enabled = true
  }
}

dokka {
  dokkaPublications.html {
    // NOTE: This path must be in sync with `mkdocs.yml`'s API nav config path
    outputDirectory.set(rootDir.resolve("docs/api"))
    includes.from(project.layout.projectDirectory.file("README.md"))
  }
}

val ktfmtVersion = libs.versions.ktfmt.get()

spotless { predeclareDeps() }

configure<SpotlessExtensionPredeclare> {
  kotlin { ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) } }
  kotlinGradle { ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) } }
  java {
    googleJavaFormat(libs.versions.gjf.get())
      .reorderImports(true)
      .reflowLongStrings(true)
      .reorderImports(true)
  }
}

// Configure spotless in subprojects
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
    java {
      googleJavaFormat(libs.versions.gjf.get())
        .reorderImports(true)
        .reflowLongStrings(true)
        .reorderImports(true)
      target("src/**/*.java")
      trimTrailingWhitespace()
      endWithNewline()
      targetExclude("**/spotless.java")
      targetExclude("**/src/test/data/**")
      targetExclude("**/*Generated.java")
    }
    kotlin {
      ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) }
      target("src/**/*.kt")
      trimTrailingWhitespace()
      endWithNewline()
      targetExclude("**/spotless.kt")
      targetExclude("**/src/test/data/**")
    }
    kotlinGradle {
      ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) }
      target("*.kts")
      trimTrailingWhitespace()
      endWithNewline()
      licenseHeaderFile(
        rootProject.file("spotless/spotless.kt"),
        "(import|plugins|buildscript|dependencies|pluginManagement|dependencyResolutionManagement)",
      )
    }
    // Apply license formatting separately for kotlin files so we can prevent it from overwriting
    // copied files
    format("licenseKotlin") {
      licenseHeaderFile(rootProject.file("spotless/spotless.kt"), "(package|@file:)")
      target("src/**/*.kt")
      targetExclude(
        "**/src/test/data/**",
        "**/AbstractMapFactory.kt",
        "**/Assisted.kt",
        "**/AssistedFactory.kt",
        "**/ClassKey.kt",
        "**/DelegateFactory.kt",
        "**/BaseDoubleCheck.kt",
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
        "**/MemoizedSequence.kt",
        "**/ProviderOfLazy.kt",
        "**/SetFactory.kt",
        "**/SetFactoryTest.kt",
        "**/StringKey.kt",
        "**/topologicalSort.kt",
        "**/TopologicalSortTest.kt",
        "**/ir/cache/*.kt",
      )
    }
    format("licenseJava") {
      licenseHeaderFile(rootProject.file("spotless/spotless.java"), "package")
      target("src/**/*.java")
      targetExclude("**/*Generated.java")
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
          freeCompilerArgs.addAll("-jvm-default=no-compatibility")
        }
      }
    }
    if ("sample" !in project.path) {
      configure<KotlinProjectExtension> { explicitApi() }
    }
  }

  plugins.withId("com.vanniktech.maven.publish") {
    if (project.path != ":compiler") {
      apply(plugin = "org.jetbrains.dokka")
    }
    configure<MavenPublishBaseExtension> {
      publishToMavenCentral(automaticRelease = true, validateDeployment = false)
    }

    // configuration required to produce unique META-INF/*.kotlin_module file names
    tasks.withType<KotlinCompile>().configureEach {
      compilerOptions { moduleName.set(project.property("POM_ARTIFACT_ID") as String) }
    }
  }

  pluginManager.withPlugin("org.jetbrains.dokka") {
    configure<DokkaExtension> {
      basePublicationsDirectory.set(layout.buildDirectory.dir("dokkaDir"))
      dokkaSourceSets.configureEach {
        skipDeprecated.set(true)
        documentedVisibilities.add(VisibilityModifier.Public)
        reportUndocumented.set(true)
        perPackageOption {
          matchingRegex.set(".*\\.internal.*")
          suppress.set(true)
        }
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
}

dependencies {
  dokka(project(":gradle-plugin"))
  dokka(project(":interop-dagger"))
  dokka(project(":runtime"))
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
