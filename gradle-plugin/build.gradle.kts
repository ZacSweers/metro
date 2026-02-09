// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.android.build.gradle.internal.lint.AndroidLintAnalysisTask
import com.android.build.gradle.internal.lint.LintModelWriterTask
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.plugin.serialization)
  `java-gradle-plugin`
  alias(libs.plugins.buildConfig)
  alias(libs.plugins.testkit)
  alias(libs.plugins.android.lint)
  alias(libs.plugins.shadow) apply false
  id("metro.base")
  id("metro.publish")
}

metroProject {
  // Lower version for Gradle compat
  progressiveMode.set(false)
  @Suppress("DEPRECATION") languageVersion.set(KotlinVersion.KOTLIN_2_0)
  @Suppress("DEPRECATION") apiVersion.set(KotlinVersion.KOTLIN_2_0)
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

  // Collect all supported Kotlin versions from compiler-compat modules
  val versionAliasesFile =
    rootProject.isolated.projectDirectory.dir("compiler-compat").file("version-aliases.txt")
  val supportedVersions =
    versionAliasesFile.asFile
      .readLines()
      .filterNot { it.isBlank() || it.startsWith('#') }
      .map { KotlinToolingVersion(it) }
      .sorted()

  buildConfigField(
    "List<String>",
    "SUPPORTED_KOTLIN_VERSIONS",
    "listOf(${supportedVersions.joinToString { "\"$it\"" }})",
  )
}

gradlePlugin {
  this.plugins {
    register("metroPlugin") {
      id = "dev.zacsweers.metro"
      implementationClass = "dev.zacsweers.metro.gradle.MetroGradleSubplugin"
    }
  }
}

kotlin.compilerOptions.optIn.add("dev.zacsweers.metro.gradle.DelicateMetroGradleApi")

/**
 * We shade guava and graph-support to avoid conflicts with other Gradle plugins that may use
 * different versions.
 */
val embedded by configurations.dependencyScope("embedded")

val embeddedClasspath by configurations.resolvable("embeddedClasspath") { extendsFrom(embedded) }

configurations.named("compileOnly").configure { extendsFrom(embedded) }

configurations.named("testImplementation").configure { extendsFrom(embedded) }

configurations.named("functionalTestImplementation").configure { extendsFrom(embedded) }

tasks.jar.configure { enabled = false }

val shadowJar =
  tasks.register<ShadowJar>("shadowJar") {
    from(java.sourceSets.main.map { it.output })
    configurations = listOf(embeddedClasspath)

    dependencies {
      exclude(dependency("org.jetbrains:.*"))
      exclude(dependency("org.intellij:.*"))
      exclude(dependency("org.jetbrains.kotlin:.*"))
    }

    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()

    relocate("com.google.common", "dev.zacsweers.metro.gradle.shaded.com.google.common")
    relocate("com.google.thirdparty", "dev.zacsweers.metro.gradle.shaded.com.google.thirdparty")
    relocate("com.google.errorprone", "dev.zacsweers.metro.gradle.shaded.com.google.errorprone")
    relocate("com.google.j2objc", "dev.zacsweers.metro.gradle.shaded.com.google.j2objc")
    relocate("com.autonomousapps", "dev.zacsweers.metro.gradle.shaded.com.autonomousapps")
  }

for (c in arrayOf("apiElements", "runtimeElements")) {
  configurations.named(c) { artifacts.removeIf { true } }
  artifacts.add(c, shadowJar)
}

tasks.withType<AndroidLintAnalysisTask>().configureEach { dependsOn(shadowJar) }

tasks.withType<LintModelWriterTask>().configureEach { dependsOn(shadowJar) }

dependencies {
  compileOnly(libs.kotlin.gradlePlugin)
  compileOnly(libs.kotlin.gradlePlugin.api)
  compileOnly(libs.kotlin.stdlib)
  implementation(libs.kotlinx.serialization.json)

  add(embedded.name, libs.graphSupport)
  add(embedded.name, libs.guava)

  lintChecks(libs.androidx.lint.gradle)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.kotlin.stdlib)
  testImplementation(libs.kotlin.test)

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

fun androidHomeOrNull(): File? {
  val localProps = rootProject.isolated.projectDirectory.file("local.properties").asFile
  if (localProps.exists()) {
    val properties = Properties()
    localProps.inputStream().use { properties.load(it) }
    val sdkHome = properties.getProperty("sdk.dir")?.let(::File)
    if (sdkHome?.exists() == true) return sdkHome
  }
  val androidHome = System.getenv("ANDROID_HOME")?.let(::File)
  return if (androidHome?.exists() == true) androidHome else null
}

tasks.withType<Test>().configureEach {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
  systemProperty(
    "com.autonomousapps.plugin-under-test.version",
    providers.gradleProperty("VERSION_NAME").get(),
  )
  systemProperty("dev.zacsweers.metro.gradle.test.kotlin-version", testCompilerVersion)
  systemProperty("metro.agpVersion", libs.versions.agp.get())
  systemProperty("metro.androidHome", androidHomeOrNull()?.absolutePath)
}

tasks
  .named { it == "publishTestKitSupportForJavaPublicationToFunctionalTestRepository" }
  .configureEach { mustRunAfter("signPluginMavenPublication") }
