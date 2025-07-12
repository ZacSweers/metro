// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
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
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(libs.versions.jvmTarget.map(JvmTarget::fromTarget))

    // Lower version for Gradle compat
    progressiveMode.set(false)
    @Suppress("DEPRECATION") languageVersion.set(KotlinVersion.KOTLIN_1_9)
    @Suppress("DEPRECATION") apiVersion.set(KotlinVersion.KOTLIN_1_9)
  }
}

gradlePlugin {
  plugins {
    create("metroPlugin") {
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
  functionalTestImplementation(libs.testkit.support)
  functionalTestImplementation(libs.testkit.truth)
  functionalTestRuntimeOnly(project(":compiler"))
  functionalTestRuntimeOnly(project(":runtime"))
}

tasks.withType<Test>().configureEach {
  jvmArgs(
    "-Dcom.autonomousapps.plugin-under-test.version=${providers.gradleProperty("VERSION_NAME").get()}",
    "-Ddev.zacsweers.metro.gradle.test.kotlin-version=${libs.versions.kotlin.get()}",
  )
}

tasks
  .named { it == "publishTestKitSupportForJavaPublicationToFunctionalTestRepository" }
  .configureEach { mustRunAfter("signPluginMavenPublication") }
