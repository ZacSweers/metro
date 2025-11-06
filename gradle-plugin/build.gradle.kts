// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import java.util.Locale
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-gradle-plugin`
  alias(libs.plugins.mavenPublish)
  alias(libs.plugins.buildConfig)
  alias(libs.plugins.testkit)
  alias(libs.plugins.android.lint)
}

tasks.withType<ValidatePlugins>().configureEach { enableStricterValidation = true }

buildConfig {
  packageName("dev.zacsweers.metro.gradle")
  useKotlinOutput {
    topLevelConstants = true
    internalVisibility = true
  }
  buildConfigField("String", "VERSION", providers.gradleProperty("VERSION_NAME").map { "\"$it\"" })
  buildConfigField("String", "PLUGIN_ID", libs.versions.pluginId.map { "\"$it\"" })
  buildConfigField("String", "BASE_KOTLIN_VERSION", libs.versions.kotlin.map { "\"$it\"" })

  // Collect all supported Kotlin versions from compiler-compat modules
  val compilerCompatDir = rootProject.isolated.projectDirectory.dir("compiler-compat").asFile
  val supportedVersions =
    fileTree(compilerCompatDir) { include("k*/version.txt") }
      .elements
      .map { files -> files.map { it.asFile.readText().trim().lowercase(Locale.US) }.sorted() }
  buildConfigField(
    "List<String>",
    "SUPPORTED_KOTLIN_VERSIONS",
    supportedVersions.map { versions -> "listOf(${versions.joinToString { "\"$it\"" }})" },
  )
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(libs.versions.jvmTarget.map(JvmTarget::fromTarget))

    // Lower version for Gradle compat
    progressiveMode.set(false)
    @Suppress("DEPRECATION") languageVersion.set(KotlinVersion.KOTLIN_2_0)
    @Suppress("DEPRECATION") apiVersion.set(KotlinVersion.KOTLIN_2_0)
  }
}

gradlePlugin {
  this.plugins {
    register("metroPlugin") {
      id = "dev.zacsweers.metro"
      implementationClass = "dev.zacsweers.metro.gradle.MetroGradleSubplugin"
    }
  }
}

dependencies {
  compileOnly(libs.kotlin.gradlePlugin)
  compileOnly(libs.kotlin.gradlePlugin.api)
  compileOnly(libs.kotlin.stdlib)

  lintChecks(libs.androidx.lint.gradle)

  functionalTestImplementation(libs.junit)
  functionalTestImplementation(libs.truth)
  functionalTestImplementation(libs.kotlin.stdlib)
  functionalTestImplementation(libs.kotlin.test)
  functionalTestImplementation(libs.testkit.support)
  functionalTestImplementation(libs.testkit.truth)
  functionalTestRuntimeOnly(project(":compiler"))
  functionalTestRuntimeOnly(project(":runtime"))
  functionalTestRuntimeOnly(project(":interop-dagger"))
}

val testCompilerVersion =
  providers.gradleProperty("metro.testCompilerVersion").orElse(libs.versions.kotlin).get()

tasks.withType<Test>().configureEach {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
  systemProperty(
    "com.autonomousapps.plugin-under-test.version",
    providers.gradleProperty("VERSION_NAME").get(),
  )
  systemProperty("dev.zacsweers.metro.gradle.test.kotlin-version", testCompilerVersion)
}

tasks
  .named { it == "publishTestKitSupportForJavaPublicationToFunctionalTestRepository" }
  .configureEach { mustRunAfter("signPluginMavenPublication") }
