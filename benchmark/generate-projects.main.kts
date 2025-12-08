// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:DependsOn("com.github.ajalt.clikt:clikt-jvm:5.0.3")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import kotlin.random.Random

class GenerateProjectsCommand : CliktCommand() {
  override fun help(context: Context): String {
    return "Generate Metro benchmark project with configurable modules and compilation modes"
  }

  private val mode by
    option("--mode", "-m", help = "Build mode: metro, anvil, or kotlin-inject-anvil")
      .enum<BuildMode>(ignoreCase = true)
      .default(BuildMode.METRO)

  private val totalModules by
    option("--count", "-c", help = "Total number of modules to generate").int().default(500)

  private val enableSharding
    get() = totalModules >= 500

  private val processor by
    option("--processor", "-p", help = "Annotation processor: ksp or kapt (anvil mode only)")
      .enum<ProcessorMode>(ignoreCase = true)
      .default(ProcessorMode.KSP)

  override fun run() {
    println("Generating benchmark project for mode: $mode with $totalModules modules")

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
                listOf(
                  "core:common-${commonRange.random()}",
                  "core:network-${networkRange.random()}",
                )
              i <= categorySize * 2 &&
                dataRange.first <= dataRange.last &&
                authRange.first <= authRange.last ->
                listOf(
                  "core:data-${dataRange.random()}",
                  "features:auth-feature-${authRange.random()}",
                )
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

    // Clean up previous generation
    println("Cleaning previous generated files...")

    listOf("core", "features", "app").forEach { layer ->
      File(layer).takeIf { it.exists() }?.deleteRecursively()
    }

    // Generate foundation module first
    println("Generating foundation module...")
    generateFoundationModule()

    // Generate all modules
    println("Generating ${allModules.size} modules...")

    allModules.forEach { generateModule(it, mode, processor) }

    // Generate app component
    println("Generating app component...")

    generateAppComponent(allModules, mode, processor)

    // Update settings.gradle.kts
    println("Updating settings.gradle.kts...")

    writeSettingsFile(allModules)

    println("Generated benchmark project with ${allModules.size} modules!")
    println("Build mode: $mode")
    if (mode == BuildMode.ANVIL) {
      println("Processor: $processor")
    }

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
  }

  enum class BuildMode {
    METRO,
    NOOP,
    ANVIL,
    KOTLIN_INJECT_ANVIL,
  }

  enum class ProcessorMode {
    KSP,
    KAPT,
  }

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

  fun String.toCamelCase(): String {
    return split("-", "_").joinToString("") { word ->
      word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
  }

  fun generateModule(module: ModuleSpec, buildMode: BuildMode, processor: ProcessorMode) {
    val moduleDir = File("${module.layer.path}/${module.name}")
    moduleDir.mkdirs()

    // Generate build.gradle.kts
    val buildFile = File(moduleDir, "build.gradle.kts")
    buildFile.writeText(generateBuildScript(module, buildMode, processor))

    // Generate source code
    val srcDir =
      File(
        moduleDir,
        "src/main/kotlin/dev/zacsweers/metro/benchmark/${module.layer.path}/${module.name.replace("-", "")}",
      )
    srcDir.mkdirs()

    val sourceFile = File(srcDir, "${module.name.toCamelCase()}.kt")
    sourceFile.writeText(generateSourceCode(module, buildMode))
  }

  fun generateBuildScript(
    module: ModuleSpec,
    buildMode: BuildMode,
    processor: ProcessorMode,
  ): String {
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
  implementation(project(":core:foundation"))
$dependencies
}

metro {
  enableGraphSharding.set($enableSharding)
  interop {
    includeJavax()
    includeAnvilForDagger()
  }
}
"""
          .trimIndent()

      BuildMode.NOOP ->
        """
plugins {
  id("org.jetbrains.kotlin.jvm")
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation("dev.zacsweers.metro:runtime:+")
  implementation(project(":core:foundation"))
$dependencies
}
"""
          .trimIndent()

      BuildMode.KOTLIN_INJECT_ANVIL ->
        """
plugins {
  id("org.jetbrains.kotlin.jvm")
  alias(libs.plugins.ksp)
}

dependencies {
  implementation("me.tatarka.inject:kotlin-inject-runtime:0.8.0")
  implementation("software.amazon.lastmile.kotlin.inject.anvil:runtime:0.1.6")
  implementation("software.amazon.lastmile.kotlin.inject.anvil:runtime-optional:0.1.6")
  implementation(project(":core:foundation"))
  ksp("me.tatarka.inject:kotlin-inject-compiler-ksp:0.8.0")
  ksp("software.amazon.lastmile.kotlin.inject.anvil:compiler:0.1.6")
$dependencies
}
"""
          .trimIndent()

      BuildMode.ANVIL ->
        when (processor) {
          ProcessorMode.KSP ->
            """
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
  alias(libs.plugins.anvil)
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation(libs.dagger.runtime)
  implementation(project(":core:foundation"))
  ksp("dev.zacsweers.anvil:compiler:0.4.1")
  ksp(libs.dagger.compiler)
$dependencies
}

anvil {
  useKsp(
    contributesAndFactoryGeneration = true,
    componentMerging = true,
  )
}
"""
              .trimIndent()

          ProcessorMode.KAPT ->
            """
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlin.kapt)
  alias(libs.plugins.anvil)
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation(libs.dagger.runtime)
  implementation(project(":core:foundation"))
  ksp("dev.zacsweers.anvil:compiler:0.4.1")
  kapt(libs.dagger.compiler)
$dependencies
}

anvil {
  useKsp(
    contributesAndFactoryGeneration = true,
    componentMerging = true,
  )
}
"""
              .trimIndent()
        }
    }
  }

  fun generateSourceCode(module: ModuleSpec, buildMode: BuildMode): String {
    val packageName =
      "dev.zacsweers.metro.benchmark.${module.layer.path}.${module.name.replace("-", "")}"
    val className = module.name.toCamelCase()

    val contributions =
      (1..module.contributionsCount).joinToString("\n\n") { i ->
        generateContribution(module, i, buildMode)
      }

    val subcomponent =
      if (module.hasSubcomponent) {
        generateSubcomponent(module, buildMode)
      } else ""

    // Generate imports for dependent API classes if this module has subcomponents
    val dependencyImports =
      if (module.hasSubcomponent) {
        module.dependencies
          .mapNotNull { dep ->
            val parts = dep.split(":")
            if (parts.size >= 2) {
              val layerName = parts[0] // "features", "core", "app"
              val moduleName = parts[1] // "auth-feature-10", "platform-55", etc.
              val cleanModuleName = moduleName.replace("-", "")
              val packagePath = "dev.zacsweers.metro.benchmark.$layerName.$cleanModuleName"
              val apiName = "${moduleName.toCamelCase()}Api"
              "import $packagePath.$apiName"
            } else null
          }
          .joinToString("\n")
      } else ""

    val imports =
      when (buildMode) {
        BuildMode.METRO,
        BuildMode.NOOP ->
          """
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesMultibinding
import com.squareup.anvil.annotations.ContributesSubcomponent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.SingleIn
import javax.inject.Inject
$dependencyImports
"""
            .trimIndent()

        BuildMode.KOTLIN_INJECT_ANVIL ->
          """
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.ContributesSubcomponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Scope
$dependencyImports
"""
            .trimIndent()

        BuildMode.ANVIL ->
          """
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesMultibinding
import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.ContributesTo
import javax.inject.Inject
import javax.inject.Scope
import javax.inject.Singleton
$dependencyImports
"""
            .trimIndent()
      }

    val scopeAnnotation =
      when (buildMode) {
        BuildMode.METRO,
        BuildMode.NOOP -> "@SingleIn(AppScope::class)"
        BuildMode.KOTLIN_INJECT_ANVIL -> "@SingleIn(AppScope::class)"
        BuildMode.ANVIL -> "@Singleton"
      }

    val scopeParam =
      when (buildMode) {
        BuildMode.METRO,
        BuildMode.NOOP -> "AppScope::class"
        BuildMode.KOTLIN_INJECT_ANVIL -> "AppScope::class"
        BuildMode.ANVIL -> "Unit::class"
      }

    return """
package $packageName

$imports
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer

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

  fun generateContribution(module: ModuleSpec, index: Int, buildMode: BuildMode): String {
    val className = module.name.toCamelCase()

    // Use deterministic random based on module name and index for consistency
    val moduleRandom = Random(module.name.hashCode() + index)
    return when (moduleRandom.nextInt(3)) {
      0 -> generateBindingContribution(className, index, buildMode)
      1 -> generateMultibindingContribution(className, index, buildMode)
      else -> generateSetMultibindingContribution(className, index, buildMode)
    }
  }

  fun generateBindingContribution(className: String, index: Int, buildMode: BuildMode): String {
    val scopeAnnotation =
      when (buildMode) {
        BuildMode.METRO,
        BuildMode.NOOP -> "@SingleIn(AppScope::class)"
        BuildMode.KOTLIN_INJECT_ANVIL -> "@SingleIn(AppScope::class)"
        BuildMode.ANVIL -> "@Singleton"
      }

    val scopeParam =
      when (buildMode) {
        BuildMode.METRO,
        BuildMode.NOOP -> "AppScope::class"
        BuildMode.KOTLIN_INJECT_ANVIL -> "AppScope::class"
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

  fun generateMultibindingContribution(
    className: String,
    index: Int,
    buildMode: BuildMode,
  ): String {
    val scopeParam =
      when (buildMode) {
        BuildMode.METRO,
        BuildMode.NOOP -> "AppScope::class"
        BuildMode.KOTLIN_INJECT_ANVIL -> "AppScope::class"
        BuildMode.ANVIL -> "Unit::class"
      }

    val multibindingAnnotation =
      when (buildMode) {
        BuildMode.KOTLIN_INJECT_ANVIL ->
          "@ContributesBinding($scopeParam, boundType = Plugin::class, multibinding = true)"
        else -> "@ContributesMultibinding($scopeParam, boundType = Plugin::class)"
      }

    return """
interface ${className}Plugin$index : Plugin {
  override fun execute(): String
}

$multibindingAnnotation
class ${className}PluginImpl$index @Inject constructor() : ${className}Plugin$index {
  override fun execute() = "${className.lowercase()}-plugin-$index"
}
"""
      .trimIndent()
  }

  fun generateSetMultibindingContribution(
    className: String,
    index: Int,
    buildMode: BuildMode,
  ): String {
    val scopeParam =
      when (buildMode) {
        BuildMode.METRO,
        BuildMode.NOOP -> "AppScope::class"
        BuildMode.KOTLIN_INJECT_ANVIL -> "AppScope::class"
        BuildMode.ANVIL -> "Unit::class"
      }

    val multibindingAnnotation =
      when (buildMode) {
        BuildMode.KOTLIN_INJECT_ANVIL ->
          "@ContributesBinding($scopeParam, boundType = Initializer::class, multibinding = true)"
        else -> "@ContributesMultibinding($scopeParam, boundType = Initializer::class)"
      }

    return """
interface ${className}Initializer$index : Initializer {
  override fun initialize()
}

$multibindingAnnotation
class ${className}InitializerImpl$index @Inject constructor() : ${className}Initializer$index {
  override fun initialize() = println("Initializing ${className.lowercase()} $index")
}
"""
      .trimIndent()
  }

  fun generateSubcomponent(module: ModuleSpec, buildMode: BuildMode): String {
    val className = module.name.toCamelCase()

    // Only use dependencies that this module actually depends on
    val availableDependencies =
      module.dependencies
        .mapNotNull { dep ->
          // Extract module name from dependency path like ":features:auth-feature-11" ->
          // "AuthFeature11Api"
          val moduleName = dep.split(":").lastOrNull()?.toCamelCase()
          if (moduleName != null) "${moduleName}Api" else null
        }
        .take(2) // Limit to 2 to avoid too many dependencies

    val parentAccessors = availableDependencies.joinToString("\n") { "  fun get$it(): $it" }

    // Generate some subcomponent-scoped bindings
    val subcomponentAccessors =
      (1..3).joinToString("\n") {
        "  fun get${className}LocalService$it(): ${className}LocalService$it"
      }

    return when (buildMode) {
      BuildMode.METRO,
      BuildMode.NOOP ->
        """
// Subcomponent-scoped services that depend on parent scope
${(1..3).joinToString("\n") { i ->
          val dependencyParams = if (availableDependencies.isNotEmpty()) {
            availableDependencies.joinToString(",\n  ") { "private val $it: $it" }
          } else {
            "// No parent dependencies available"
          }

          """interface ${className}LocalService$i

@SingleIn(${className}Scope::class)
@ContributesBinding(${className}Scope::class)
class ${className}LocalServiceImpl$i @Inject constructor(${if (availableDependencies.isNotEmpty()) "\n  $dependencyParams\n" else ""}) : ${className}LocalService$i"""
        }}

@SingleIn(${className}Scope::class)
@ContributesSubcomponent(
  scope = ${className}Scope::class,
  parentScope = AppScope::class
)
interface ${className}Subcomponent {
  ${if (availableDependencies.isNotEmpty()) "// Access parent scope bindings\n$parentAccessors\n  \n" else ""}// Access subcomponent scope bindings
$subcomponentAccessors

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun create${className}Subcomponent(): ${className}Subcomponent
  }
}

object ${className}Scope
"""
      BuildMode.KOTLIN_INJECT_ANVIL ->
        """
// Subcomponent-scoped services that depend on parent scope
${(1..3).joinToString("\n") { i ->
          val dependencyParams = if (availableDependencies.isNotEmpty()) {
            availableDependencies.joinToString(",\n  ") { "private val $it: $it" }
          } else {
            "// No parent dependencies available"
          }

          """interface ${className}LocalService$i

@${className}Scope
@ContributesBinding(${className}Scope::class)
class ${className}LocalServiceImpl$i @Inject constructor(${if (availableDependencies.isNotEmpty()) "\n  $dependencyParams\n" else ""}) : ${className}LocalService$i"""
        }}

@${className}Scope
@ContributesSubcomponent(
  scope = ${className}Scope::class
)
interface ${className}Subcomponent {
  ${if (availableDependencies.isNotEmpty()) "// Access parent scope bindings\n$parentAccessors\n  \n" else ""}// Access subcomponent scope bindings
$subcomponentAccessors

  @ContributesSubcomponent.Factory(AppScope::class)
  interface Factory {
    fun create${className}Subcomponent(): ${className}Subcomponent
  }
}

@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class ${className}Scope
"""

      BuildMode.ANVIL ->
        """
// Subcomponent-scoped services that depend on parent scope
${(1..3).joinToString("\n") { i ->
          val dependencyParams = if (availableDependencies.isNotEmpty()) {
            availableDependencies.joinToString(",\n  ") { "private val $it: $it" }
          } else {
            "// No parent dependencies available"
          }

          """interface ${className}LocalService$i

@${className}Scope
@ContributesBinding(${className}Scope::class)
class ${className}LocalServiceImpl$i @Inject constructor(${if (availableDependencies.isNotEmpty()) "\n  $dependencyParams\n" else ""}) : ${className}LocalService$i"""
        }}

@${className}Scope
@ContributesSubcomponent(
  scope = ${className}Scope::class,
  parentScope = Unit::class
)
interface ${className}Subcomponent {
  ${if (availableDependencies.isNotEmpty()) "// Access parent scope bindings\n$parentAccessors\n  \n" else ""}// Access subcomponent scope bindings
$subcomponentAccessors

  @ContributesTo(Unit::class)
  interface Factory {
    fun create${className}Subcomponent(): ${className}Subcomponent
  }
}

@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class ${className}Scope
"""
    }.trimIndent()
  }

  fun generateAccessors(allModules: List<ModuleSpec>): String {
    // Generate accessors for services that actually exist in each module
    val scopedBindings =
      allModules.flatMap { module ->
        (1..module.contributionsCount).mapNotNull { index ->
          // Use the same deterministic random logic as generateContribution
          val moduleRandom = Random(module.name.hashCode() + index)
          when (moduleRandom.nextInt(3)) {
            0 -> "${module.name.toCamelCase()}Service$index" // binding contribution
            else -> null // multibindings and other types don't need individual accessors
          }
        }
      }

    // Group into chunks to avoid extremely long interfaces
    return scopedBindings
      .chunked(50)
      .mapIndexed { chunkIndex, chunk ->
        val accessors = chunk.joinToString("\n") { "  fun get$it(): $it" }
        """
// Accessor interface $chunkIndex to force generation of scoped bindings
interface AccessorInterface$chunkIndex {
$accessors
}"""
      }
      .joinToString("\n\n")
  }

  fun generateFoundationModule() {
    val foundationDir = File("core/foundation")
    foundationDir.mkdirs()

    // Create build.gradle.kts
    val buildFile = File(foundationDir, "build.gradle.kts")
    val buildScript =
      """
plugins {
  id("org.jetbrains.kotlin.jvm")
}

dependencies {
  implementation("javax.inject:javax.inject:1")
}
"""
    buildFile.writeText(buildScript.trimIndent())

    // Create source directory
    val srcDir =
      File(foundationDir, "src/main/kotlin/dev/zacsweers/metro/benchmark/core/foundation")
    srcDir.mkdirs()

    // Create common interfaces
    val sourceFile = File(srcDir, "CommonInterfaces.kt")
    val sourceCode =
      """
package dev.zacsweers.metro.benchmark.core.foundation

// Common interfaces for multibindings
interface Plugin {
  fun execute(): String
}

interface Initializer {
  fun initialize()
}
"""
    sourceFile.writeText(sourceCode.trimIndent())

    // Create plain Kotlin file without any DI annotations
    val plainFile = File(srcDir, "PlainKotlinFile.kt")
    val plainSourceCode =
      """
package dev.zacsweers.metro.benchmark.core.foundation

/**
 * A simple plain Kotlin class without any dependency injection annotations.
 * Used for benchmarking compiler plugin overhead on non-DI files.
 */
class PlainDataProcessor {
  private var counter = 0

  fun processData(input: String): String {
    counter++
    return "Processed: ${'$'}input (#${'$'}counter)"
  }

  fun getProcessedCount(): Int {
    return counter
  }
}
"""
    plainFile.writeText(plainSourceCode.trimIndent())
  }

  fun generateAppComponent(
    allModules: List<ModuleSpec>,
    buildMode: BuildMode,
    processor: ProcessorMode,
  ) {
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
  application
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation("dev.zacsweers.metro:runtime:+")
  implementation(project(":core:foundation"))

  // Depend on all generated modules to aggregate everything
${allModules.joinToString("\n") { "  implementation(project(\":${it.layer.path}:${it.name}\"))" }}
}

application {
  mainClass = "dev.zacsweers.metro.benchmark.app.component.AppComponentKt"
}

metro {
  // reportsDestination.set(layout.buildDirectory.dir("metro"))
  enableGraphSharding.set($enableSharding)
  interop {
    includeJavax()
    includeAnvilForDagger()
  }
}
"""

        BuildMode.NOOP ->
          """
plugins {
  id("org.jetbrains.kotlin.jvm")
  application
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation("dev.zacsweers.metro:runtime:+")
  implementation(project(":core:foundation"))

  // Depend on all generated modules to aggregate everything
${allModules.joinToString("\n") { "  implementation(project(\":${it.layer.path}:${it.name}\"))" }}
}

application {
  mainClass = "dev.zacsweers.metro.benchmark.app.component.AppComponentKt"
}
"""

        BuildMode.KOTLIN_INJECT_ANVIL ->
          """
plugins {
  id("org.jetbrains.kotlin.jvm")
  alias(libs.plugins.ksp)
  application
}

dependencies {
  implementation("me.tatarka.inject:kotlin-inject-runtime:0.8.0")
  implementation("software.amazon.lastmile.kotlin.inject.anvil:runtime:0.1.6")
  implementation("software.amazon.lastmile.kotlin.inject.anvil:runtime-optional:0.1.6")
  implementation(project(":core:foundation"))
  ksp("me.tatarka.inject:kotlin-inject-compiler-ksp:0.8.0")
  ksp("software.amazon.lastmile.kotlin.inject.anvil:compiler:0.1.6")

  // Depend on all generated modules to aggregate everything
${allModules.joinToString("\n") { "  implementation(project(\":${it.layer.path}:${it.name}\"))" }}
}

application {
  mainClass = "dev.zacsweers.metro.benchmark.app.component.AppComponentKt"
}
"""

        BuildMode.ANVIL ->
          when (processor) {
            ProcessorMode.KSP ->
              """
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
  alias(libs.plugins.anvil)
  application
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation(libs.dagger.runtime)
  implementation(project(":core:foundation"))
  ksp("dev.zacsweers.anvil:compiler:0.4.1")
  ksp(libs.dagger.compiler)

  // Depend on all generated modules to aggregate everything
${allModules.joinToString("\n") { "  implementation(project(\":${it.layer.path}:${it.name}\"))" }}
}

anvil {
  useKsp(
    contributesAndFactoryGeneration = true,
    componentMerging = true,
  )
}

application {
  mainClass = "dev.zacsweers.metro.benchmark.app.component.AppComponentKt"
}
"""
            ProcessorMode.KAPT ->
              """
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlin.kapt)
  alias(libs.plugins.anvil)
  application
}

dependencies {
  implementation("javax.inject:javax.inject:1")
  implementation("dev.zacsweers.anvil:annotations:0.4.1")
  implementation(libs.dagger.runtime)
  implementation(project(":core:foundation"))
  ksp("dev.zacsweers.anvil:compiler:0.4.1")
  kapt(libs.dagger.compiler)

  // Depend on all generated modules to aggregate everything
${allModules.joinToString("\n") { "  implementation(project(\":${it.layer.path}:${it.name}\"))" }}
}

anvil {
  useKsp(
    contributesAndFactoryGeneration = true,
    componentMerging = true,
  )
}

application {
  mainClass = "dev.zacsweers.metro.benchmark.app.component.AppComponentKt"
}
"""
          }
      }

    buildFile.writeText(buildScript.trimIndent())

    val srcDir = File(appDir, "src/main/kotlin/dev/zacsweers/metro/benchmark/app/component")
    srcDir.mkdirs()

    val sourceFile = File(srcDir, "AppComponent.kt")
    // Generate imports for all the service classes that will have accessors
    val serviceImports =
      allModules
        .flatMap { module ->
          (1..module.contributionsCount).mapNotNull { index ->
            val moduleRandom = Random(module.name.hashCode() + index)
            when (moduleRandom.nextInt(3)) {
              0 -> {
                val packageName =
                  "dev.zacsweers.metro.benchmark.${module.layer.path}.${module.name.replace("-", "")}"
                val serviceName = "${module.name.toCamelCase()}Service$index"
                "import $packageName.$serviceName"
              }
              else -> null
            }
          }
        }
        .joinToString("\n")

    val sourceCode =
      when (buildMode) {
        BuildMode.METRO ->
          """
package dev.zacsweers.metro.benchmark.app.component

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer
$serviceImports

${generateAccessors(allModules)}

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppComponent : ${(0 until (allModules.size / 50 + 1)).joinToString(", ") { "AccessorInterface$it" }} {
  // Multibinding accessors
  fun getAllPlugins(): Set<Plugin>
  fun getAllInitializers(): Set<Initializer>

  // Multibind declarations
  @Multibinds
  fun bindPlugins(): Set<Plugin>

  @Multibinds
  fun bindInitializers(): Set<Initializer>
}

/**
 * Creates and fully initializes the dependency graph.
 * This is the primary entry point for benchmarking graph creation and initialization.
 */
fun createAndInitialize(): AppComponent {
  val graph = createGraph<AppComponent>()
  // Force full initialization by accessing all multibindings
  graph.getAllPlugins()
  graph.getAllInitializers()
  return graph
}

fun main() {
  val graph = createAndInitialize()
  val fields = graph.javaClass.declaredFields.size
  val methods = graph.javaClass.declaredMethods.size
  val plugins = graph.getAllPlugins()
  val initializers = graph.getAllInitializers()

  println("Metro benchmark graph successfully created!")
  println("  - Fields: ${'$'}fields")
  println("  - Methods: ${'$'}methods")
  println("  - Plugins: ${'$'}{plugins.size}")
  println("  - Initializers: ${'$'}{initializers.size}")
  println("  - Total modules: ${allModules.size}")
  println("  - Total contributions: ${allModules.sumOf { it.contributionsCount }}")
}
"""

        BuildMode.NOOP ->
          """
package dev.zacsweers.metro.benchmark.app.component

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer
$serviceImports

${generateAccessors(allModules)}

/**
 * NOOP mode - Metro runtime is present but compiler plugin is not applied.
 * This interface has the same structure as Metro mode but no code generation occurs.
 * Used as a baseline to measure build times without any DI compiler plugin overhead.
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppComponent : ${(0 until (allModules.size / 50 + 1)).joinToString(", ") { "AccessorInterface$it" }} {
  // Multibinding accessors
  fun getAllPlugins(): Set<Plugin>
  fun getAllInitializers(): Set<Initializer>

  // Multibind declarations
  @Multibinds
  fun bindPlugins(): Set<Plugin>

  @Multibinds
  fun bindInitializers(): Set<Initializer>
}

fun main() {
  // NOOP mode - no DI graph is created since the compiler plugin is not applied.
  // This serves as a baseline for measuring pure Kotlin compilation overhead.
  println("NOOP benchmark completed!")
  println("  - Metro runtime present but compiler plugin NOT applied")
  println("  - Total modules: ${allModules.size}")
  println("  - Total contributions: ${allModules.sumOf { it.contributionsCount }}")
  println("  - This is a baseline measurement for build times without DI compiler overhead")
}
"""

        BuildMode.KOTLIN_INJECT_ANVIL ->
          """
package dev.zacsweers.metro.benchmark.app.component

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer
$serviceImports

@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
abstract class AppComponent {
  // Multibinding accessors
  abstract val allPlugins: Set<Plugin>
  abstract val allInitializers: Set<Initializer>
}

/**
 * Creates and fully initializes the dependency graph.
 * This is the primary entry point for benchmarking graph creation and initialization.
 */
fun createAndInitialize(): AppComponent {
  val graph = AppComponent::class.create()
  // Force full initialization by accessing all multibindings
  graph.allPlugins
  graph.allInitializers
  return graph
}

fun main() {
  val appComponent = createAndInitialize()
  val fields = appComponent.javaClass.declaredFields.size
  val methods = appComponent.javaClass.declaredMethods.size
  val plugins = appComponent.allPlugins
  val initializers = appComponent.allInitializers

  println("Pure Kotlin-inject-anvil benchmark graph successfully created!")
  println("  - Fields: ${'$'}fields")
  println("  - Methods: ${'$'}methods")
  println("  - Plugins: ${'$'}{plugins.size}")
  println("  - Initializers: ${'$'}{initializers.size}")
  println("  - Total modules: ${allModules.size}")
  println("  - Total contributions: ${allModules.sumOf { it.contributionsCount }}")
}
"""

        BuildMode.ANVIL ->
          """
package dev.zacsweers.metro.benchmark.app.component

import com.squareup.anvil.annotations.MergeComponent
import javax.inject.Singleton
import dagger.multibindings.Multibinds
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer
$serviceImports

${generateAccessors(allModules)}

@Singleton
@MergeComponent(Unit::class)
interface AppComponent : ${(0 until (allModules.size / 50 + 1)).joinToString(", ") { "AccessorInterface$it" }} {
  // Multibinding accessors
  fun getAllPlugins(): Set<Plugin>
  fun getAllInitializers(): Set<Initializer>

  @MergeComponent.Factory
  interface Factory {
    fun create(): AppComponent
  }
}

// Multibind declarations for Dagger
@dagger.Module
interface AppComponentMultibinds {
  @Multibinds
  fun bindPlugins(): Set<Plugin>

  @Multibinds
  fun bindInitializers(): Set<Initializer>
}

/**
 * Creates and fully initializes the dependency graph.
 * This is the primary entry point for benchmarking graph creation and initialization.
 */
fun createAndInitialize(): AppComponent {
  val graph = DaggerAppComponent.factory().create()
  // Force full initialization by accessing all multibindings
  graph.getAllPlugins()
  graph.getAllInitializers()
  return graph
}

fun main() {
  val component = createAndInitialize()
  val fields = component.javaClass.declaredFields.size
  val methods = component.javaClass.declaredMethods.size
  val plugins = component.getAllPlugins()
  val initializers = component.getAllInitializers()

  println("Anvil benchmark graph successfully created!")
  println("  - Fields: ${'$'}fields")
  println("  - Methods: ${'$'}methods")
  println("  - Plugins: ${'$'}{plugins.size}")
  println("  - Initializers: ${'$'}{initializers.size}")
  println("  - Total modules: ${allModules.size}")
  println("  - Total contributions: ${allModules.sumOf { it.contributionsCount }}")
}
"""
      }

    sourceFile.writeText(sourceCode.trimIndent())
  }

  fun writeSettingsFile(allModules: List<ModuleSpec>) {
    val settingsFile = File("generated-projects.txt")
    val includes =
      listOf(":core:foundation") +
        allModules.map { ":${it.layer.path}:${it.name}" } +
        ":app:component"
    val content = includes.joinToString("\n")
    settingsFile.writeText(content)
  }
}

// Execute the command
GenerateProjectsCommand().main(args)
