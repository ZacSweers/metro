// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import java.io.File
import kotlin.random.Random

// Parse command line arguments
var mode = "metro"
var totalModules = 500

var i = 0

while (i < args.size) {
  when (args[i]) {
    "--mode" -> {
      mode = args.getOrNull(i + 1) ?: "metro"
      i += 2
    }
    "--count" -> {
      totalModules = args.getOrNull(i + 1)?.toIntOrNull() ?: 500
      i += 2
    }
    else -> {
      println("Unknown argument: ${args[i]}")
      println("Usage: kotlin generate-projects.main.kts [--mode metro|anvil] [--count <number>]")
      kotlin.system.exitProcess(1)
    }
  }
}

enum class BuildMode {
  METRO,
  ANVIL;

  companion object {
    fun from(str: String) =
      when (str.lowercase()) {
        "anvil" -> ANVIL
        "metro" -> METRO
        else -> error("Unknown mode: $str. Use 'anvil' or 'metro'")
      }
  }
}

val buildMode = BuildMode.from(mode)

println("Generating benchmark project for mode: $buildMode with $totalModules modules")

/**
 * Generates a benchmark project with configurable number of modules organized in layers:
 * - Core layer (~16% of total): fundamental utilities, data models, networking
 * - Features layer (~70% of total): business logic features
 * - App layer (~14% of total): glue code, dependency wiring, UI integration
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

// Calculate layer sizes based on total modules
val coreCount = (totalModules * 0.16).toInt().coerceAtLeast(5)
val featuresCount = (totalModules * 0.70).toInt().coerceAtLeast(5)
val appCount = (totalModules - coreCount - featuresCount).coerceAtLeast(1)

// Module architecture design
val coreModules =
  (1..coreCount).map { i ->
    val categorySize = coreCount / 6
    ModuleSpec(
      name =
        when {
          i <= categorySize -> "common-$i"
          i <= categorySize * 2 -> "network-$i"
          i <= categorySize * 3 -> "data-$i"
          i <= categorySize * 4 -> "utils-$i"
          i <= categorySize * 5 -> "platform-$i"
          else -> "shared-$i"
        },
      layer = Layer.CORE,
    )
  }

val featureModules =
  (1..featuresCount).map { i ->
    val categorySize = featuresCount / 6
    val coreCategory = coreCount / 6

    // Calculate actual ranges based on what modules exist
    val commonRange = 1..(coreCategory.coerceAtLeast(1))
    val networkRange = (coreCategory + 1)..(coreCategory * 2).coerceAtLeast(2)
    val dataRange = (coreCategory * 2 + 1)..(coreCategory * 3).coerceAtLeast(3)
    val utilsRange = (coreCategory * 3 + 1)..(coreCategory * 4).coerceAtLeast(4)
    val platformRange = (coreCategory * 4 + 1)..(coreCategory * 5).coerceAtLeast(5)
    val sharedRange = (coreCategory * 5 + 1)..coreCount

    val authRange = 1..(categorySize.coerceAtLeast(1))
    val userRange = (categorySize + 1)..(categorySize * 2).coerceAtLeast(2)
    val contentRange = (categorySize * 2 + 1)..(categorySize * 3).coerceAtLeast(3)
    val socialRange = (categorySize * 3 + 1)..(categorySize * 4).coerceAtLeast(4)
    val commerceRange = (categorySize * 4 + 1)..(categorySize * 5).coerceAtLeast(5)

    ModuleSpec(
      name =
        when {
          i <= categorySize -> "auth-feature-$i"
          i <= categorySize * 2 -> "user-feature-$i"
          i <= categorySize * 3 -> "content-feature-$i"
          i <= categorySize * 4 -> "social-feature-$i"
          i <= categorySize * 5 -> "commerce-feature-$i"
          else -> "analytics-feature-$i"
        },
      layer = Layer.FEATURES,
      dependencies =
        when {
          i <= categorySize &&
            commonRange.first <= commonRange.last &&
            networkRange.first <= networkRange.last ->
            listOf("core:common-${commonRange.random()}", "core:network-${networkRange.random()}")
          i <= categorySize * 2 &&
            dataRange.first <= dataRange.last &&
            authRange.first <= authRange.last ->
            listOf("core:data-${dataRange.random()}", "features:auth-feature-${authRange.random()}")
          i <= categorySize * 3 &&
            utilsRange.first <= utilsRange.last &&
            userRange.first <= userRange.last ->
            listOf(
              "core:utils-${utilsRange.random()}",
              "features:user-feature-${userRange.random()}",
            )
          i <= categorySize * 4 &&
            platformRange.first <= platformRange.last &&
            contentRange.first <= contentRange.last ->
            listOf(
              "core:platform-${platformRange.random()}",
              "features:content-feature-${contentRange.random()}",
            )
          i <= categorySize * 5 &&
            socialRange.first <= socialRange.last &&
            userRange.first <= userRange.last ->
            listOf(
              "features:social-feature-${socialRange.random()}",
              "features:user-feature-${userRange.random()}",
            )
          else ->
            if (
              commerceRange.first <= commerceRange.last && sharedRange.first <= sharedRange.last
            ) {
              listOf(
                "features:commerce-feature-${commerceRange.random()}",
                "core:shared-${sharedRange.random()}",
              )
            } else emptyList()
        },
    )
  }

val appModules =
  (1..appCount).map { i ->
    val categorySize = appCount / 4
    val featureCategory = featuresCount / 6
    val coreCategory = coreCount / 6

    // Calculate actual ranges for features
    val authRange = 1..(featureCategory.coerceAtLeast(1))
    val userRange = (featureCategory + 1)..(featureCategory * 2).coerceAtLeast(2)
    val contentRange = (featureCategory * 2 + 1)..(featureCategory * 3).coerceAtLeast(3)
    val socialRange = (featureCategory * 3 + 1)..(featureCategory * 4).coerceAtLeast(4)
    val commerceRange = (featureCategory * 4 + 1)..(featureCategory * 5).coerceAtLeast(5)
    val analyticsRange = (featureCategory * 5 + 1)..featuresCount

    // Calculate actual ranges for core
    val commonRange = 1..(coreCategory.coerceAtLeast(1))
    val platformRange = (coreCategory * 4 + 1)..(coreCategory * 5).coerceAtLeast(5)

    // Calculate actual ranges for app
    val uiRange = 1..(categorySize.coerceAtLeast(1))
    val navigationRange = (categorySize + 1)..(categorySize * 2).coerceAtLeast(2)
    val integrationRange = (categorySize * 2 + 1)..(categorySize * 3).coerceAtLeast(3)

    ModuleSpec(
      name =
        when {
          i <= categorySize -> "ui-$i"
          i <= categorySize * 2 -> "navigation-$i"
          i <= categorySize * 3 -> "integration-$i"
          else -> "app-glue-$i"
        },
      layer = Layer.APP,
      dependencies =
        when {
          i <= categorySize &&
            authRange.first <= authRange.last &&
            userRange.first <= userRange.last &&
            platformRange.first <= platformRange.last ->
            listOf(
              "features:auth-feature-${authRange.random()}",
              "features:user-feature-${userRange.random()}",
              "core:platform-${platformRange.random()}",
            )
          i <= categorySize * 2 &&
            contentRange.first <= contentRange.last &&
            uiRange.first <= uiRange.last ->
            listOf(
              "features:content-feature-${contentRange.random()}",
              "app:ui-${uiRange.random()}",
            )
          i <= categorySize * 3 &&
            commerceRange.first <= commerceRange.last &&
            analyticsRange.first <= analyticsRange.last &&
            navigationRange.first <= navigationRange.last ->
            listOf(
              "features:commerce-feature-${commerceRange.random()}",
              "features:analytics-feature-${analyticsRange.random()}",
              "app:navigation-${navigationRange.random()}",
            )
          else ->
            if (
              integrationRange.first <= integrationRange.last &&
                commonRange.first <= commonRange.last
            ) {
              listOf(
                "app:integration-${integrationRange.random()}",
                "core:common-${commonRange.random()}",
              )
            } else emptyList()
        },
      hasSubcomponent =
        i <= (appCount * 0.1).toInt().coerceAtLeast(1), // ~10% of app modules have subcomponents
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

  return when (buildMode) {
    BuildMode.METRO ->
      """
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

    BuildMode.ANVIL ->
      """
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation(libs.dagger.runtime)
  ksp("dev.zacsweers.anvil:compiler:0.4.1")
  ksp(libs.dagger.compiler)
$dependencies
}
"""
        .trimIndent()
  }
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

  val imports =
    when (buildMode) {
      BuildMode.METRO ->
        """
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesMultibinding
import com.squareup.anvil.annotations.ContributesSubcomponent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import javax.inject.Inject
"""
          .trimIndent()

      BuildMode.ANVIL ->
        """
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesMultibinding
import com.squareup.anvil.annotations.ContributesSubcomponent
import javax.inject.Inject
import javax.inject.Singleton
"""
          .trimIndent()
    }

  val scopeAnnotation =
    when (buildMode) {
      BuildMode.METRO -> "@SingleIn(AppScope::class)"
      BuildMode.ANVIL -> "@Singleton"
    }

  val scopeParam =
    when (buildMode) {
      BuildMode.METRO -> "AppScope::class"
      BuildMode.ANVIL -> "Unit::class"
    }

  return """
package $packageName

$imports

// Main module interface
interface ${className}Api

// Implementation
$scopeAnnotation
@ContributesBinding($scopeParam)
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
  val scopeAnnotation =
    when (buildMode) {
      BuildMode.METRO -> "@SingleIn(AppScope::class)"
      BuildMode.ANVIL -> "@Singleton"
    }

  val scopeParam =
    when (buildMode) {
      BuildMode.METRO -> "AppScope::class"
      BuildMode.ANVIL -> "Unit::class"
    }

  return """
interface ${className}Service$index

$scopeAnnotation
@ContributesBinding($scopeParam)
class ${className}ServiceImpl$index @Inject constructor() : ${className}Service$index
"""
    .trimIndent()
}

fun generateMultibindingContribution(className: String, index: Int): String {
  val scopeParam =
    when (buildMode) {
      BuildMode.METRO -> "AppScope::class"
      BuildMode.ANVIL -> "Unit::class"
    }

  return """
interface ${className}Plugin$index {
  fun execute(): String
}

@ContributesMultibinding($scopeParam)
class ${className}PluginImpl$index @Inject constructor() : ${className}Plugin$index {
  override fun execute() = "${className.lowercase()}-plugin-$index"
}
"""
    .trimIndent()
}

fun generateSetMultibindingContribution(className: String, index: Int): String {
  val scopeParam =
    when (buildMode) {
      BuildMode.METRO -> "AppScope::class"
      BuildMode.ANVIL -> "Unit::class"
    }

  return """
interface ${className}Initializer

@ContributesMultibinding($scopeParam)
class ${className}Initializer$index @Inject constructor() : ${className}Initializer {
  fun initialize() = println("Initializing ${className.lowercase()} $index")
}
"""
    .trimIndent()
}

fun generateSubcomponent(module: ModuleSpec): String {
  val className = module.name.toCamelCase()

  return when (buildMode) {
    BuildMode.METRO ->
      """
@SingleIn(${className}Scope::class)
@ContributesSubcomponent(
  scope = ${className}Scope::class,
  parentScope = AppScope::class
)
interface ${className}Subcomponent

object ${className}Scope
"""
    BuildMode.ANVIL ->
      """
@ContributesSubcomponent(parentScope = Unit::class)
interface ${className}Subcomponent {
  @ContributesSubcomponent.Factory
  interface Factory {
    fun create(): ${className}Subcomponent
  }
}
"""
  }.trimIndent()
}

fun generateAppComponent() {
  val appDir = File("app/component")
  appDir.mkdirs()

  val buildFile = File(appDir, "build.gradle.kts")
  val buildScript =
    when (buildMode) {
      BuildMode.METRO ->
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

      BuildMode.ANVIL ->
        """
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation(libs.dagger.runtime)
  ksp("dev.zacsweers.anvil:compiler:0.4.1")
  ksp(libs.dagger.compiler)

  // Depend on all app layer modules to aggregate everything
${appModules.take(10).joinToString("\n") { "  implementation(project(\":app:${it.name}\"))" }}
}
"""
    }

  buildFile.writeText(buildScript.trimIndent())

  val srcDir = File(appDir, "src/main/kotlin/dev/zacsweers/metro/benchmark/app/component")
  srcDir.mkdirs()

  val sourceFile = File(srcDir, "AppComponent.kt")
  val sourceCode =
    when (buildMode) {
      BuildMode.METRO ->
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

      BuildMode.ANVIL ->
        """
package dev.zacsweers.metro.benchmark.app.component

import com.squareup.anvil.annotations.MergeComponent
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component
@MergeComponent(Unit::class)
interface AppComponent {
  @Component.Factory
  interface Factory {
    fun create(): AppComponent
  }
}

fun main() {
  val component = DaggerAppComponent.factory().create()
  // Count the number of methods as a proxy for the number of providers
  val providerCount = component.javaClass.declaredMethods.size
  println("Successfully created benchmark graph with ${'$'}providerCount providers")
}
"""
    }

  sourceFile.writeText(sourceCode.trimIndent())
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

// Clean up previous generation
println("Cleaning previous generated files...")

listOf("core", "features", "app").forEach { layer ->
  File(layer).takeIf { it.exists() }?.deleteRecursively()
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

println(
  "- Core: ${coreModules.size} (${String.format("%.1f", coreModules.size.toDouble() / allModules.size * 100)}%)"
)

println(
  "- Features: ${featureModules.size} (${String.format("%.1f", featureModules.size.toDouble() / allModules.size * 100)}%)"
)

println(
  "- App: ${appModules.size} (${String.format("%.1f", appModules.size.toDouble() / allModules.size * 100)}%)"
)

println("Total contributions: ${allModules.sumOf { it.contributionsCount }}")

println("Subcomponents: ${allModules.count { it.hasSubcomponent }}")
