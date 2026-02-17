// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessExtensionPredeclare
import com.diffplug.spotless.LineEnding

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
val ktfmtVersion = catalog.findVersion("ktfmt").get().requiredVersion
val gjfVersion = catalog.findVersion("gjf").get().requiredVersion

val isolatedRoot = rootProject.isolated

val spotlessDir =
  if (isolatedRoot.projectDirectory.dir("spotless").asFile.exists()) {
    isolatedRoot.projectDirectory.dir("spotless")
  } else {
    isolatedRoot.projectDirectory.dir("../spotless")
  }

val configDir =
  if (isolatedRoot.projectDirectory.dir("config").asFile.exists()) {
    isolatedRoot.projectDirectory.dir("config")
  } else {
    isolatedRoot.projectDirectory.dir("../config")
  }

fun loadExcludes(name: String) =
  providers.fileContents(configDir.file(name)).asText.map { text ->
    text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
  }

// TODO spotless is too eager here, sigh
val ktLicenseExcludes = loadExcludes("license-header-excludes-kt.txt").get()
val javaLicenseExcludes = loadExcludes("license-header-excludes-java.txt").get()

apply(plugin = "com.diffplug.spotless")

val isRootProject = this == rootProject

if (isRootProject) {
  configure<SpotlessExtensionPredeclare> {
    kotlin { ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) } }
    kotlinGradle { ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) } }
    java {
      googleJavaFormat(gjfVersion).reorderImports(true).reflowLongStrings(true).reorderImports(true)
    }
  }
}

configure<SpotlessExtension> {
  if (isRootProject) {
    predeclareDeps()
  }
  setLineEndings(LineEnding.GIT_ATTRIBUTES_FAST_ALLSAME)
  format("misc") {
    target("*.gradle", "*.md", ".gitignore")
    trimTrailingWhitespace()
    leadingTabsToSpaces(2)
    endWithNewline()
  }
  java {
    googleJavaFormat(gjfVersion).reorderImports(true).reflowLongStrings(true).reorderImports(true)
    target("src/**/*.java")
    trimTrailingWhitespace()
    endWithNewline()
    targetExclude("**/spotless.java")
    targetExclude(javaLicenseExcludes)
  }
  kotlin {
    ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) }
    target("src/**/*.kt")
    trimTrailingWhitespace()
    endWithNewline()
    targetExclude("**/spotless.kt")
    targetExclude(ktLicenseExcludes)
  }
  kotlinGradle {
    ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) }
    target("*.kts")
    trimTrailingWhitespace()
    endWithNewline()
    licenseHeaderFile(
      spotlessDir.file("spotless.kt"),
      "(@file:|import|plugins|buildscript|dependencies|pluginManagement|dependencyResolutionManagement)",
    )
  }
  format("licenseKotlin") {
    licenseHeaderFile(spotlessDir.file("spotless.kt"), "(package|@file:)")
    target("src/**/*.kt")
    targetExclude(ktLicenseExcludes)
  }
  format("licenseJava") {
    licenseHeaderFile(spotlessDir.file("spotless.java"), "package")
    target("src/**/*.java")
    targetExclude(javaLicenseExcludes)
  }
}
