// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.intellijPlatform)
  id("metro.base")
  id("metro.spotless")
}

metroProject { jvmTarget.set("21") }

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  intellijPlatform {
    intellijIdeaUltimate(
      // Source this from the first IU version in ide-versions.txt
      providers.fileContents(layout.projectDirectory.file("ide-versions.txt")).asText.map { text ->
        text.lineSequence().first { it.startsWith("IU") }.removePrefix("IU:")
      }
    )
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Starter)
  }
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.testJunit5)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.junit.jupiter.platformLauncher)
}

tasks.test {
  dependsOn(gradle.includedBuild("metro").task(":installForFunctionalTest"))
  useJUnitPlatform()
  // IDE Starter tests need significant memory and time
  jvmArgs("-Xmx4g")
  // Timeout per test — IDE download + Gradle import + analysis can be slow
  systemProperty("junit.jupiter.execution.timeout.default", "15m")
  systemProperty(
    "metro.testProject",
    layout.projectDirectory.dir("test-project").asFile.absolutePath,
  )
  systemProperty(
    "metro.ideVersions",
    layout.projectDirectory.file("ide-versions.txt").asFile.absolutePath,
  )
  // Suppress "Could not find installation home path" warning from Driver SDK logging
  systemProperty("idea.home.path", layout.projectDirectory.asFile.absolutePath)
}
