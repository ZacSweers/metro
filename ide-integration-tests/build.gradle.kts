// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.intellijPlatform)
  alias(libs.plugins.gradleTestRetry)
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
        text.lineSequence().firstOrNull { it.startsWith("IU") }?.removePrefix("IU:")
          // Just cover for CI where we may run with only one IDE in the file
          ?: "2025.3.2"
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
  // When METRO_PREBUILT is set, artifacts are already in build/functionalTestRepo (e.g., from CI).
  gradle.includedBuilds
    .find { it.name == "metro" }
    ?.let { dependsOn(it.task(":installForFunctionalTest")) }
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

  // Fork per test to isolate IDE Starter's ShutdownListener, which blocks new tests in the same JVM
  // after a failure. Without this, retries and subsequent tests are killed.
  forkEvery = 1

  retry {
    maxRetries.set(1)
    failOnPassedAfterRetry.set(true)
    failOnSkippedAfterRetry.set(true)
  }
}
