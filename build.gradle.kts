// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import kotlinx.validation.ExperimentalBCVApi

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.lint) apply false
  alias(libs.plugins.dokka)
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.mavenPublish) apply false
  alias(libs.plugins.atomicfu) apply false
  alias(libs.plugins.spotless)
  alias(libs.plugins.binaryCompatibilityValidator)
  alias(libs.plugins.poko) apply false
  alias(libs.plugins.wire) apply false
  id("metro.yarnNode")
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

subprojects {
  group = project.property("GROUP") as String
  version = project.property("VERSION_NAME") as String
}

dependencies {
  dokka(project(":gradle-plugin"))
  dokka(project(":interop-dagger"))
  dokka(project(":interop-guice"))
  dokka(project(":interop-jakarta"))
  dokka(project(":interop-javax"))
  dokka(project(":metrox-android"))
  dokka(project(":metrox-viewmodel"))
  dokka(project(":metrox-viewmodel-compose"))
  dokka(project(":runtime"))
}
