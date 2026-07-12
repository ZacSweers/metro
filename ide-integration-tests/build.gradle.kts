// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.intellij.platform.gradle.models.Coordinates

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.intellijPlatform)
  alias(libs.plugins.gradleTestRetry)
  id("metro.base")
}

metroProject { jvmTarget.set("21") }

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

val intellijVersion =
  providers.fileContents(layout.projectDirectory.file("ide-versions.txt")).asText.map { text ->
    text
      .lineSequence()
      .firstOrNull { it.startsWith("IU") }
      ?.removePrefix("IU:")
      // Strip build type or inline comment, keep just the version/build number
      ?.substringBefore(":")
      ?.substringBefore("#")
      ?.trim()
      // Just cover for CI where we may run with only one IDE in the file
      ?: "2025.3.2"
  }

val starterVersion = intellijVersion.map { version ->
  val is253 = version.startsWith("2025.3") || version.startsWith("253.")
  if (is253) "253.30387.90" else "261.23567.138"
}

dependencies {
  intellijPlatform {
    intellijIdeaUltimate(
      // Source this from the first IU version in ide-versions.txt.
      // Stable entries use marketing version (e.g., 2025.3.2), resolved from releases repo.
      // Prerelease entries use build number (e.g., 261.22158.182), resolved from snapshots repo.
      intellijVersion
    )
    bundledPlugin("org.jetbrains.kotlin")
    // Starter embeds platform classes, so keep 253 hosts on Starter 253. Cap newer hosts at 261
    // because Starter 262 deleted IdeProductProvider and is compiled with JVM target 25. Declare
    // the pre-2.18 dependency set because its new 262 product artifacts do not exist at version
    // 261.
    listOf(
        Coordinates("com.jetbrains.intellij.tools", "ide-starter-squashed"),
        Coordinates("com.jetbrains.intellij.tools", "ide-starter-junit5"),
        Coordinates("com.jetbrains.intellij.tools", "ide-starter-driver"),
        Coordinates("com.jetbrains.intellij.driver", "driver-client"),
        Coordinates("com.jetbrains.intellij.driver", "driver-sdk"),
        Coordinates("com.jetbrains.intellij.driver", "driver-model"),
      )
      .forEach { testPlatformDependency(it, starterVersion) }
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
  jvmArgs("-Xmx4g", "-Xlog:cds=off")
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
    failOnPassedAfterRetry.set(false)
    failOnSkippedAfterRetry.set(true)
  }

  testLogging { showStandardStreams = false }
}
