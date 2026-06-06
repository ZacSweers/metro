// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.intellijPlatform)
  id("metro.base")
}

metroProject { jvmTarget.set(libs.versions.ideaJvmTarget) }

java { toolchain { languageVersion.set(libs.versions.ideaJvmTarget.map(JavaLanguageVersion::of)) } }

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  intellijPlatform {
    intellijIdeaUltimate("2026.1.3")
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Platform)
  }

  testImplementation(project(":runtime"))
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
}

intellijPlatform {
  pluginConfiguration {
    name = "Metro"
    version = project.version.toString()

    ideaVersion {
      sinceBuild = "261"
    }
  }

  pluginVerification {
    ides {
      create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1.3")
      // Quail 1 is marketed as 2026.1.1, but the Android Studio release feed keys it as 2026.1.1.8.
      create(IntelliJPlatformType.AndroidStudio, "2026.1.1.8")
    }
  }
}

tasks.withType<VerifyPluginTask>().configureEach {
  setJvmArgs(jvmArgs.filterNot { it == "--sun-misc-unsafe-memory-access=allow" })
}

tasks.test {
  dependsOn(":runtime:jvmJar")
  systemProperty(
    "metro.runtime.jar",
    project(":runtime")
      .layout
      .buildDirectory
      .file("libs/runtime-jvm-${project.version}.jar")
      .get()
      .asFile
      .absolutePath,
  )
}
