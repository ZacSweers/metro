// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import java.io.File
import kotlin.random.Random

/**
 * Generates a benchmark project with ~500 modules organized in layers:
 * - Core layer (80 modules): fundamental utilities, data models, networking
 * - Features layer (350 modules): business logic features
 * - App layer (70 modules): glue code, dependency wiring, UI integration
 */
data class ModuleSpec(
  val name: String,
  val layer: Layer,
  val dependencies: List<String> = emptyList(),
  val contributionsCount: Int = Random.nextInt(1, 11), // 1-10 contributions per module
  val hasSubcomponent: Boolean = false,
)

enum class Layer(val path: String) {
  CORE("core"),
  FEATURES("features"),
  APP("app"),
}

// Module architecture design
val coreModules =
  (1..80).map { i ->
    ModuleSpec(
      name =
        when {
          i <= 10 -> "common-$i"
          i <= 20 -> "network-$i"
          i <= 35 -> "data-$i"
          i <= 50 -> "utils-$i"
          i <= 65 -> "platform-$i"
          else -> "shared-$i"
        },
      layer = Layer.CORE,
    )
  }

val featureModules =
  (1..350).map { i ->
    ModuleSpec(
      name =
        when {
          i <= 50 -> "auth-feature-$i"
          i <= 100 -> "user-feature-$i"
          i <= 150 -> "content-feature-$i"
          i <= 200 -> "social-feature-$i"
          i <= 250 -> "commerce-feature-$i"
          i <= 300 -> "analytics-feature-$i"
          else -> "misc-feature-$i"
        },
      layer = Layer.FEATURES,
      dependencies =
        when {
          i <= 50 ->
            listOf("core:common-${Random.nextInt(1, 11)}", "core:network-${Random.nextInt(11, 21)}")
          i <= 100 ->
            listOf(
              "core:data-${Random.nextInt(21, 36)}",
              "features:auth-feature-${Random.nextInt(1, 11)}",
            )
          i <= 150 ->
            listOf(
              "core:utils-${Random.nextInt(36, 51)}",
              "features:user-feature-${Random.nextInt(51, 76)}",
            )
          i <= 200 ->
            listOf(
              "core:platform-${Random.nextInt(51, 66)}",
              "features:content-feature-${Random.nextInt(101, 126)}",
            )
          i <= 250 ->
            listOf(
              "features:social-feature-${Random.nextInt(151, 176)}",
              "features:user-feature-${Random.nextInt(51, 101)}",
            )
          i <= 300 ->
            listOf(
              "features:commerce-feature-${Random.nextInt(201, 226)}",
              "core:data-${Random.nextInt(21, 36)}",
            )
          else ->
            listOf(
              "features:analytics-feature-${Random.nextInt(251, 276)}",
              "core:shared-${Random.nextInt(66, 81)}",
            )
        },
    )
  }

val appModules =
  (1..70).map { i ->
    ModuleSpec(
      name =
        when {
          i <= 20 -> "ui-$i"
          i <= 40 -> "navigation-$i"
          i <= 55 -> "integration-$i"
          else -> "app-glue-$i"
        },
      layer = Layer.APP,
      dependencies =
        when {
          i <= 20 ->
            listOf(
              "features:auth-feature-${Random.nextInt(1, 51)}",
              "features:user-feature-${Random.nextInt(51, 101)}",
              "core:platform-${Random.nextInt(51, 66)}",
            )
          i <= 40 ->
            listOf(
              "features:content-feature-${Random.nextInt(101, 151)}",
              "app:ui-${Random.nextInt(1, 21)}",
            )
          i <= 55 ->
            listOf(
              "features:commerce-feature-${Random.nextInt(201, 251)}",
              "features:analytics-feature-${Random.nextInt(251, 301)}",
              "app:navigation-${Random.nextInt(21, 41)}",
            )
          else ->
            listOf(
              "app:integration-${Random.nextInt(41, 56)}",
              "core:common-${Random.nextInt(1, 11)}",
            )
        },
      hasSubcomponent = i <= 5, // First 5 app modules have subcomponents
    )
  }

val allModules = coreModules + featureModules + appModules

fun generateModule(module: ModuleSpec) {
  val moduleDir = File("${module.layer.path}/${module.name}")
  moduleDir.mkdirs()

  // Generate build.gradle.kts
  val buildFile = File(moduleDir, "build.gradle.kts")
  buildFile.writeText(generateBuildScript(module))

  // Generate source code
  val srcDir =
    File(
      moduleDir,
      "src/main/kotlin/dev/zacsweers/metro/benchmark/${module.layer.path}/${module.name.replace("-", "")}",
    )
  srcDir.mkdirs()

  val sourceFile = File(srcDir, "${module.name.toCamelCase()}.kt")
  sourceFile.writeText(generateSourceCode(module))
}

fun generateBuildScript(module: ModuleSpec): String {
  val dependencies =
    module.dependencies.joinToString("\n") { dep -> "  implementation(project(\":$dep\"))" }

  return """
plugins {
  id("org.jetbrains.kotlin.jvm")
  id("dev.zacsweers.metro")
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation("dev.zacsweers.metro:runtime:+")
$dependencies
}

metro {
  interop {
    includeJavax()
    includeAnvil()
  }
}
"""
    .trimIndent()
}

fun generateSourceCode(module: ModuleSpec): String {
  val packageName =
    "dev.zacsweers.metro.benchmark.${module.layer.path}.${module.name.replace("-", "")}"
  val className = module.name.toCamelCase()

  val contributions =
    (1..module.contributionsCount).joinToString("\n\n") { i -> generateContribution(module, i) }

  val subcomponent =
    if (module.hasSubcomponent) {
      generateSubcomponent(module)
    } else ""

  return """
package $packageName

import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesMultibinding
import com.squareup.anvil.annotations.ContributesSubcomponent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import javax.inject.Inject

// Main module interface
interface ${className}Api

// Implementation
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ${className}Impl @Inject constructor() : ${className}Api

$contributions

$subcomponent
"""
    .trimIndent()
}

fun generateContribution(module: ModuleSpec, index: Int): String {
  val className = module.name.toCamelCase()

  return when (Random.nextInt(3)) {
    0 -> generateBindingContribution(className, index)
    1 -> generateMultibindingContribution(className, index)
    else -> generateSetMultibindingContribution(className, index)
  }
}

fun generateBindingContribution(className: String, index: Int): String {
  return """
interface ${className}Service$index

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ${className}ServiceImpl$index @Inject constructor() : ${className}Service$index
"""
    .trimIndent()
}

fun generateMultibindingContribution(className: String, index: Int): String {
  return """
interface ${className}Plugin$index {
  fun execute(): String
}

@ContributesMultibinding(AppScope::class)
class ${className}PluginImpl$index @Inject constructor() : ${className}Plugin$index {
  override fun execute() = "${className.lowercase()}-plugin-$index"
}
"""
    .trimIndent()
}

fun generateSetMultibindingContribution(className: String, index: Int): String {
  return """
@ContributesMultibinding(AppScope::class)
class ${className}Initializer$index @Inject constructor() {
  fun initialize() = println("Initializing ${className.lowercase()} $index")
}
"""
    .trimIndent()
}

fun generateSubcomponent(module: ModuleSpec): String {
  val className = module.name.toCamelCase()

  return """
@SingleIn(${className}Scope::class)
@ContributesSubcomponent(
  scope = ${className}Scope::class,
  parentScope = AppScope::class
)
interface ${className}Subcomponent

object ${className}Scope
"""
    .trimIndent()
}

fun generateAppComponent() {
  val appDir = File("app/component")
  appDir.mkdirs()

  val buildFile = File(appDir, "build.gradle.kts")
  buildFile.writeText(
    """
plugins {
  id("org.jetbrains.kotlin.jvm")
  id("dev.zacsweers.metro")
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation("dev.zacsweers.metro:runtime:+")

  // Depend on all app layer modules to aggregate everything
${appModules.take(10).joinToString("\n") { "  implementation(project(\":app:${it.name}\"))" }}
}

metro {
  interop {
    includeJavax()
    includeAnvil()
  }
}
"""
      .trimIndent()
  )

  val srcDir = File(appDir, "src/main/kotlin/dev/zacsweers/metro/benchmark/app/component")
  srcDir.mkdirs()

  val sourceFile = File(srcDir, "AppComponent.kt")
  sourceFile.writeText(
    """
package dev.zacsweers.metro.benchmark.app.component

import com.squareup.anvil.annotations.MergeComponent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph

@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
interface AppComponent

fun main() {
  val graph = createGraph<AppComponent>()
  println("Successfully created benchmark graph with ${'$'}{graph.javaClass.declaredMethods.size} providers")
}
"""
      .trimIndent()
  )
}

fun writeSettingsFile() {
  val settingsFile = File("generated-projects.txt")
  val includes = allModules.map { ":${it.layer.path}:${it.name}" } + ":app:component"
  val content = includes.joinToString("\n")
  settingsFile.writeText(content)
}

fun String.toCamelCase(): String {
  return split("-", "_").joinToString("") { word ->
    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
  }
}

// Generate all modules
println("Generating ${allModules.size} modules...")

allModules.forEach(::generateModule)

// Generate app component
println("Generating app component...")

generateAppComponent()

// Update settings.gradle.kts
println("Updating settings.gradle.kts...")

writeSettingsFile()

println("Generated benchmark project with ${allModules.size} modules!")

println("Modules by layer:")

println("- Core: ${coreModules.size}")

println("- Features: ${featureModules.size}")

println("- App: ${appModules.size}")

println("Total contributions: ${allModules.sumOf { it.contributionsCount }}")

println("Subcomponents: ${allModules.count { it.hasSubcomponent }}")
