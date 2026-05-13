// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:DependsOn("com.github.ajalt.clikt:clikt-jvm:5.1.0")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import kotlin.random.Random

class GenerateProjectsCommand : CliktCommand() {
  override fun help(context: Context): String {
    return "Generate Metro benchmark project with configurable modules and compilation modes"
  }

  private val buildMode by
    option(
        "--mode",
        "-m",
        help = "Build mode: metro, dagger, kotlin_inject_anvil, koin, vanilla, or metro_noop",
      )
      .enum<BuildMode>(ignoreCase = true)
      .default(BuildMode.METRO)

  private val totalModules by
    option("--count", "-c", help = "Total number of modules to generate").int().default(500)

  private val enableSharding
    get() = if (graphShardingExplicitlySet) enableGraphShardingFlag else totalModules >= 500

  private val processor by
    option("--processor", "-p", help = "Annotation processor: ksp or kapt (dagger mode only)")
      .enum<ProcessorMode>(ignoreCase = true)
      .default(ProcessorMode.KSP)

  private val multiplatform by
    option("--multiplatform", help = "Generate multiplatform project (Metro mode only)")
      .flag(default = false)

  private val providerMultibindings by
    option(
        "--provider-multibindings",
        help =
          "Wrap multibinding accessors in a provider form (e.g., a provider of `Set<E>` instead of `Set<E>`). " +
            "Metro mode generates the preferred function-syntax form `() -> Set<E>`. Dagger mode uses the " +
            "classic `Provider<Set<E>>` form. Useful for benchmarking `SetFactory`/`MapFactory` behavior.",
      )
      .flag(default = false)

  private val enableReports by
    option("--enable-reports", help = "Enable Metro graph reports for debugging (Metro mode only).")
      .flag(default = false)

  private val enableTracing by
    option("--enable-tracing", help = "Enable Metro compiler tracing (Metro mode only).")
      .flag(default = false)

  private val enableGraphShardingFlag by
    option(
        "--enable-graph-sharding",
        help =
          "Enable graph sharding (Metro mode only). By default, sharding is automatically enabled for 500+ modules.",
      )
      .flag("--no-enable-graph-sharding", default = false, defaultForHelp = "auto (500+ modules)")

  private val graphShardingExplicitlySet by lazy {
    // Check if the flag was explicitly provided on the command line
    "--enable-graph-sharding" in args || "--no-enable-graph-sharding" in args
  }

  private val enableSwitchingProviders by
    option(
        "--enable-switching-providers",
        help =
          "Enable switching providers for deferred class loading (Metro mode only). Reduces graph initialization time by deferring bindings' class init until requested.",
      )
      .flag(default = false)

  private val parallelThreads by
    option(
        "--parallel-threads",
        help =
          "Number of threads for parallel graph validation (Metro mode only). 0 (default) disables parallelism.",
      )
      .int()
      .default(0)

  private val l2ChildrenPerL1: Int
    get() = ((totalModules - 500).coerceAtLeast(0) / 300).coerceAtMost(5)

  private val l3ChildrenPerL2: Int
    get() = ((totalModules - 1000).coerceAtLeast(0) / 333).coerceAtMost(3)

  override fun run() {
    if (multiplatform && buildMode != BuildMode.METRO && buildMode != BuildMode.KOIN) {
      echo("Error: --multiplatform flag is only supported with Metro or Koin mode", err = true)
      return
    }

    val modeDesc = if (multiplatform) "$buildMode (multiplatform)" else buildMode.toString()
    echo("Generating benchmark project for mode: $modeDesc with $totalModules modules")

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
                    commonRange.first <= commonRange.last &&
                    socialRange.first <= socialRange.last
                ) {
                  listOf(
                    "app:integration-${integrationRange.random()}",
                    "core:common-${commonRange.random()}",
                    "features:social-feature-${socialRange.random()}",
                  )
                } else emptyList()
            },
          hasSubcomponent =
            i <= (appCount * 0.1).toInt().coerceAtLeast(1), // ~10% of app modules have subcomponents
        )
      }

    val allModules = coreModules + featureModules + appModules

    // Clean up previous generation
    echo("Cleaning previous generated files...")

    listOf("core", "features", "app").forEach { layer ->
      File(layer).takeIf { it.exists() }?.deleteRecursively()
    }

    // Generate foundation module first
    echo("Generating foundation module...")
    generateFoundationModule(multiplatform)

    // Generate all modules
    echo("Generating ${allModules.size} modules...")

    allModules.forEach { generateModule(it, processor) }

    // Generate app component
    echo("Generating app component...")

    generateAppComponent(allModules, processor)

    // Update settings.gradle.kts
    echo("Updating settings.gradle.kts...")

    writeSettingsFile(allModules)

    echo("Generated benchmark project with ${allModules.size} modules!")
    echo("Build mode: $buildMode")
    if (buildMode == BuildMode.DAGGER) {
      echo("Processor: $processor")
      if (enableSwitchingProviders) {
        echo("Fast init: enabled (deferred class loading)")
      }
    }
    if (providerMultibindings) {
      val form =
        when (buildMode) {
          BuildMode.METRO -> "() -> Set<E>"
          else -> "Provider<Set<E>>"
        }
      println("Provider multibindings: enabled (using $form instead of Set<E>)")
    }
    if (buildMode == BuildMode.METRO) {
      echo("Graph sharding: ${if (enableSharding) "enabled" else "disabled"}")
      if (enableSwitchingProviders) {
        echo("Switching providers: enabled (deferred class loading)")
      }
      if (parallelThreads > 0) {
        echo("Parallel threads: $parallelThreads")
      }
    }

    echo("Modules by layer:")

    echo(
      "- Core: ${coreModules.size} (${String.format("%.1f", coreModules.size.toDouble() / allModules.size * 100)}%)"
    )

    echo(
      "- Features: ${featureModules.size} (${String.format("%.1f", featureModules.size.toDouble() / allModules.size * 100)}%)"
    )

    echo(
      "- App: ${appModules.size} (${String.format("%.1f", appModules.size.toDouble() / allModules.size * 100)}%)"
    )

    echo("Total contributions: ${allModules.sumOf { it.contributionsCount }}")

    val l1Count = allModules.count { it.hasSubcomponent }
    val totalSubcomponents = l1Count * (1 + l2ChildrenPerL1 * (1 + l3ChildrenPerL2))
    echo("Graph extensions: $l1Count L1")
    if (l2ChildrenPerL1 > 0) {
      echo("  - L2 children per L1: $l2ChildrenPerL1 (${l1Count * l2ChildrenPerL1} total)")
    }
    if (l3ChildrenPerL2 > 0) {
      echo(
        "  - L3 children per L2: $l3ChildrenPerL2 (${l1Count * l2ChildrenPerL1 * l3ChildrenPerL2} total)"
      )
    }
    echo("  - Total: $totalSubcomponents")
  }

  enum class BuildMode {
    METRO,
    /** Metro compiler plugin applied but no Metro annotations - measures plugin overhead */
    METRO_NOOP,
    /** Pure Kotlin with no DI framework at all - true baseline */
    VANILLA,
    DAGGER,
    KOTLIN_INJECT_ANVIL,
    /**
     * Koin with koin-annotations + K2 compiler plugin.
     *
     * Koin is a runtime service-locator; the compiler plugin performs some reachability/validation
     * checks but the runtime graph is still a KClass-keyed map walked at `get()`/`getAll()` time.
     * Functional-equivalence caveats vs Metro/Dagger/kotlin-inject:
     * - No multibindings. `Set<Plugin>`/`Set<Initializer>` are synthesized via
     *   [org.koin.core.Koin.getAll] and wrapped with `.toSet()` at the accessor boundary.
     * - No cross-Gradle-module aggregation. We rely on `@ComponentScan` at a common root package so
     *   the annotations-driven plugin walks the dependency tree.
     * - Subcomponents are not nested containers in Koin. They are emulated with flat scopes
     *   (`@Scope`/`@Scoped` + `KoinScopeComponent`) which is cheaper at runtime; this is a real
     *   property of Koin, not a measurement artifact.
     * - Accessor interfaces are not emitted: Koin's `@Singleton` classes are registered but lazy;
     *   nothing forces realization beyond the multibinding `getAll` calls.
     */
    KOIN,
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
    val contributionsCount: Int =
      Random(name.hashCode()).nextInt(1, 11), // 1-10 contributions per module, seeded by name
    val hasSubcomponent: Boolean = false,
  )

  enum class Layer(val path: String) {
    CORE("core"),
    FEATURES("features"),
    APP("app"),
  }

  data class SubcomponentLevel(
    val name: String,
    val scopeName: String,
    val parentScopeRef: String,
    val serviceCount: Int,
    val parentServiceNames: List<String>,
  )

  fun String.toCamelCase(): String {
    return split("-", "_").joinToString("") { word ->
      word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
  }

  fun generateModule(module: ModuleSpec, processor: ProcessorMode) {
    val moduleDir = File("${module.layer.path}/${module.name}")
    moduleDir.mkdirs()

    // Generate build.gradle.kts
    val buildFile = File(moduleDir, "build.gradle.kts")
    buildFile.writeText(generateBuildScript(module, processor))

    val srcPath =
      if (multiplatform && (buildMode == BuildMode.METRO || buildMode == BuildMode.KOIN))
        "src/commonMain/kotlin"
      else "src/main/kotlin"
    val srcDir =
      File(
        moduleDir,
        "$srcPath/dev/zacsweers/metro/benchmark/${module.layer.path}/${module.name.replace("-", "")}",
      )
    srcDir.mkdirs()

    val sourceFile = File(srcDir, "${module.name.toCamelCase()}.kt")
    sourceFile.writeText(generateSourceCode(module))
  }

  fun generateBuildScript(module: ModuleSpec, processor: ProcessorMode): String {
    val dependencies =
      module.dependencies.joinToString("\n") { dep -> "    implementation(project(\":$dep\"))" }
    val jvmDependencies =
      module.dependencies.joinToString("\n") { dep -> "  implementation(project(\":$dep\"))" }
    // Koin multiplatform uses an 8-space indent for commonMain { dependencies { ... } } nesting.
    val koinCommonDependencies =
      module.dependencies.joinToString("\n") { dep -> "        implementation(project(\":$dep\"))" }
    return when (buildMode) {
      BuildMode.METRO -> {
        if (multiplatform) {
          """
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("dev.zacsweers.metro")
}

val enableLinux = findProperty("benchmark.native.linux")?.toString()?.toBoolean() ?: false
val enableWindows = findProperty("benchmark.native.windows")?.toString()?.toBoolean() ?: false
${metroDsl()}
kotlin {
  jvm()
  js(IR) { nodejs() }
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs { nodejs() }
  macosArm64()
  macosX64()
  if (enableLinux) linuxX64()
  if (enableWindows) mingwX64()

  sourceSets {
    commonMain {
      dependencies {
        implementation("dev.zacsweers.metro:runtime:+")
        implementation(project(":core:foundation"))
$dependencies
      }
    }
  }
}
"""
            .trimIndent()
        } else {
          """
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.zacsweers.metro")
}
${metroDsl()}
dependencies {
  implementation("dev.zacsweers.metro:runtime:+")
  implementation(project(":core:foundation"))
$jvmDependencies
}
"""
            .trimIndent()
        }
      }

      BuildMode.METRO_NOOP ->
        """
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.zacsweers.metro")
  id("dev.zacsweers.metro")
}

dependencies {
  implementation(project(":core:foundation"))
$jvmDependencies
}
"""
          .trimIndent()

      BuildMode.VANILLA ->
        """
plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation(project(":core:foundation"))
$jvmDependencies
}
"""
          .trimIndent()

      BuildMode.KOTLIN_INJECT_ANVIL ->
        """
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
}

dependencies {
  implementation(libs.kotlinInject.runtime)
  implementation(libs.kotlinInject.anvil.runtime)
  implementation(libs.kotlinInject.anvil.runtime.optional)
  implementation(project(":core:foundation"))
  ksp(libs.kotlinInject.compiler)
  ksp(libs.kotlinInject.anvil.compiler)
$dependencies
}
"""
          .trimIndent()

      BuildMode.KOIN ->
        if (multiplatform) {
          val koinCommon =
            module.dependencies.joinToString("\n") { dep ->
              "        implementation(project(\":$dep\"))"
            }
          """
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.koin.compiler)
}

val enableLinux = findProperty("benchmark.native.linux")?.toString()?.toBoolean() ?: false
val enableWindows = findProperty("benchmark.native.windows")?.toString()?.toBoolean() ?: false

kotlin {
  jvm()
  js(IR) { nodejs() }
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs { nodejs() }
  macosArm64()
  macosX64()
  if (enableLinux) linuxX64()
  if (enableWindows) mingwX64()

  sourceSets {
    commonMain {
      dependencies {
        implementation(libs.koin.core)
        implementation(libs.koin.annotations)
        implementation(project(":core:foundation"))
$koinCommon
      }
    }
  }
}
"""
            .trimIndent()
        } else {
          """
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.koin.compiler)
}

dependencies {
  implementation(libs.koin.core)
  implementation(libs.koin.annotations)
  implementation(project(":core:foundation"))
$jvmDependencies
}
"""
            .trimIndent()
        }

      BuildMode.DAGGER ->
        when (processor) {
          ProcessorMode.KSP ->
            """
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
  alias(libs.plugins.anvil)
}

dependencies {
  implementation(libs.javaxInject)
  implementation(libs.anvil.annotations)
  implementation(libs.dagger.runtime)
  implementation(project(":core:foundation"))
  ksp(libs.anvil.kspCompiler)
  ksp(libs.dagger.compiler)
$dependencies
}

anvil {
  useKsp(
    contributesAndFactoryGeneration = true,
    componentMerging = true,
  )
}
${daggerKspFastInit()}
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
  implementation(libs.javaxInject)
  implementation(libs.anvil.annotations)
  implementation(libs.dagger.runtime)
  implementation(project(":core:foundation"))
  ksp(libs.anvil.kspCompiler)
  kapt(libs.dagger.compiler)
$dependencies
}

anvil {
  useKsp(
    contributesAndFactoryGeneration = true,
    componentMerging = true,
  )
}
${daggerKaptFastInit()}
"""
              .trimIndent()
        }
    }
  }

  fun daggerKspFastInit(): String {
    return if (enableSwitchingProviders) {
      """
ksp {
  arg("dagger.fastInit", "enabled")
}"""
    } else ""
  }

  fun daggerKaptFastInit(): String {
    return if (enableSwitchingProviders) {
      """
kapt {
  arguments {
    arg("dagger.fastInit", "enabled")
  }
}"""
    } else ""
  }

  fun generateSourceCode(module: ModuleSpec): String {
    val packageName =
      "dev.zacsweers.metro.benchmark.${module.layer.path}.${module.name.replace("-", "")}"
    val className = module.name.toCamelCase()

    // Koin has a structurally different annotation model (no @ContributesBinding, no scope param),
    // so we emit a dedicated source form rather than parameterizing the main path. Must bail here
    // (before `contributions` is materialized) since generateContribution dispatchees call `error`
    // for KOIN.
    if (buildMode == BuildMode.KOIN) {
      return generateKoinSourceCode(module, packageName, className)
    }

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
        BuildMode.METRO ->
          """
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Scope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
$dependencyImports
"""
            .trimIndent()

        BuildMode.METRO_NOOP,
        BuildMode.VANILLA ->
          """
// Pure Kotlin - no DI annotations
$dependencyImports
"""
            .trimIndent()

        BuildMode.KOTLIN_INJECT_ANVIL ->
          """
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.ContributesSubcomponent
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Scope
$dependencyImports
"""
            .trimIndent()

        BuildMode.DAGGER ->
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

        BuildMode.KOIN -> error("KOIN path handled above by generateKoinSourceCode")
      }

    val scopeAnnotation =
      when (buildMode) {
        BuildMode.METRO -> "@SingleIn(AppScope::class)"
        BuildMode.METRO_NOOP,
        BuildMode.VANILLA -> "" // No DI annotations
        BuildMode.KOTLIN_INJECT_ANVIL -> "@SingleIn(AppScope::class)"
        BuildMode.DAGGER -> "@Singleton"
        BuildMode.KOIN -> error("KOIN path handled above by generateKoinSourceCode")
      }

    val scopeParam =
      when (buildMode) {
        BuildMode.METRO -> "AppScope::class"
        BuildMode.METRO_NOOP,
        BuildMode.VANILLA -> "" // No DI annotations
        BuildMode.KOTLIN_INJECT_ANVIL -> "AppScope::class"
        BuildMode.DAGGER -> "Unit::class"
        BuildMode.KOIN -> error("KOIN path handled above by generateKoinSourceCode")
      }

    // For METRO_NOOP and VANILLA, generate plain Kotlin without annotations
    if (buildMode == BuildMode.METRO_NOOP || buildMode == BuildMode.VANILLA) {
      // Generate the same class structure as other modes, just without DI annotations
      val contributions =
        (1..module.contributionsCount)
          .map { i ->
            // Use deterministic random for consistency with other modes
            val moduleRandom = Random(module.name.hashCode() + i)
            when (moduleRandom.nextInt(3)) {
              0 ->
                """// Binding contribution $i
interface ${className}Service$i

class ${className}ServiceImpl$i : ${className}Service$i"""
              1 ->
                """// Plugin contribution $i
interface ${className}Plugin$i : Plugin {
  override fun execute(): String
}

class ${className}PluginImpl$i : ${className}Plugin$i {
  override fun execute() = "${className.lowercase()}-plugin-$i"
}"""
              else ->
                """// Initializer contribution $i
interface ${className}Initializer$i : Initializer {
  override fun initialize()
}

class ${className}InitializerImpl$i : ${className}Initializer$i {
  override fun initialize() = println("Initializing ${className.lowercase()} $i")
}"""
            }
          }
          .joinToString("\n\n")

      // Generate subcomponent equivalent for vanilla/metro-noop (plain classes)
      val subcomponentCode =
        if (module.hasSubcomponent) {
          generateVanillaSubcomponentHierarchy(className)
        } else ""

      return """
package $packageName

// Plain Kotlin without DI annotations
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer

// Main module interface
interface ${className}Api

// Implementation (no DI - just a plain class)
class ${className}Impl : ${className}Api

$contributions

$subcomponentCode
"""
        .trimIndent()
    }

    // Generate accessor interface for this module's scoped bindings
    val accessorBindings =
      (1..module.contributionsCount).mapNotNull { index ->
        val moduleRandom = Random(module.name.hashCode() + index)
        when (moduleRandom.nextInt(3)) {
          0 -> "${className}Service$index"
          else -> null
        }
      }

    val accessorInterface =
      if (accessorBindings.isNotEmpty()) {
        val accessors = accessorBindings.joinToString("\n") { "  fun get$it(): $it" }
        """
// Accessor interface to force generation of scoped bindings
@ContributesTo($scopeParam)
interface ${className}AccessorInterface {
$accessors
}"""
      } else ""

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
${if (buildMode == BuildMode.DAGGER) "" else "@Inject\n"}class ${className}Impl${if (buildMode == BuildMode.DAGGER) " @Inject constructor()" else ""} : ${className}Api

$contributions
$accessorInterface
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
    // METRO_NOOP/VANILLA don't generate DI contributions; KOIN is handled in
    // generateKoinSourceCode.
    val scopeAnnotation =
      when (buildMode) {
        BuildMode.METRO -> "@SingleIn(AppScope::class)"
        BuildMode.METRO_NOOP,
        BuildMode.VANILLA -> "" // No DI annotations
        BuildMode.KOTLIN_INJECT_ANVIL -> "@SingleIn(AppScope::class)"
        BuildMode.DAGGER -> "@Singleton"
        BuildMode.KOIN -> error("KOIN uses generateKoinSourceCode, not this path")
      }

    val scopeParam =
      when (buildMode) {
        BuildMode.METRO -> "AppScope::class"
        BuildMode.METRO_NOOP,
        BuildMode.VANILLA -> "" // No DI annotations
        BuildMode.KOTLIN_INJECT_ANVIL -> "AppScope::class"
        BuildMode.DAGGER -> "Unit::class"
        BuildMode.KOIN -> error("KOIN uses generateKoinSourceCode, not this path")
      }

    val injectOnClass = buildMode != BuildMode.DAGGER
    return """
interface ${className}Service$index

$scopeAnnotation
@ContributesBinding($scopeParam)
${if (injectOnClass) "@Inject\n" else ""}class ${className}ServiceImpl$index${if (injectOnClass) "" else " @Inject constructor()"} : ${className}Service$index
"""
      .trimIndent()
  }

  fun generateMultibindingContribution(
    className: String,
    index: Int,
    buildMode: BuildMode,
  ): String {
    // METRO_NOOP/VANILLA don't generate multibindings; KOIN is handled in generateKoinSourceCode.
    val scopeParam =
      when (buildMode) {
        BuildMode.METRO -> "AppScope::class"
        BuildMode.METRO_NOOP,
        BuildMode.VANILLA -> "" // No DI annotations
        BuildMode.KOTLIN_INJECT_ANVIL -> "AppScope::class"
        BuildMode.DAGGER -> "Unit::class"
        BuildMode.KOIN -> error("KOIN uses generateKoinSourceCode, not this path")
      }

    val multibindingAnnotation =
      when (buildMode) {
        BuildMode.METRO -> "@ContributesIntoSet($scopeParam, binding = binding<Plugin>())"
        BuildMode.KOTLIN_INJECT_ANVIL ->
          "@ContributesBinding($scopeParam, boundType = Plugin::class, multibinding = true)"
        else -> "@ContributesMultibinding($scopeParam, boundType = Plugin::class)"
      }

    val injectOnClass = buildMode != BuildMode.DAGGER
    return """
interface ${className}Plugin$index : Plugin {
  override fun execute(): String
}

$multibindingAnnotation
${if (injectOnClass) "@Inject\n" else ""}class ${className}PluginImpl$index${if (injectOnClass) "" else " @Inject constructor()"} : ${className}Plugin$index {
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
    // METRO_NOOP/VANILLA don't generate set multibindings; KOIN is handled in
    // generateKoinSourceCode.
    val scopeParam =
      when (buildMode) {
        BuildMode.METRO -> "AppScope::class"
        BuildMode.METRO_NOOP,
        BuildMode.VANILLA -> "" // No DI annotations
        BuildMode.KOTLIN_INJECT_ANVIL -> "AppScope::class"
        BuildMode.DAGGER -> "Unit::class"
        BuildMode.KOIN -> error("KOIN uses generateKoinSourceCode, not this path")
      }

    val multibindingAnnotation =
      when (buildMode) {
        BuildMode.METRO -> "@ContributesIntoSet($scopeParam, binding = binding<Initializer>())"
        BuildMode.KOTLIN_INJECT_ANVIL ->
          "@ContributesBinding($scopeParam, boundType = Initializer::class, multibinding = true)"
        else -> "@ContributesMultibinding($scopeParam, boundType = Initializer::class)"
      }

    val injectOnClass = buildMode != BuildMode.DAGGER
    return """
interface ${className}Initializer$index : Initializer {
  override fun initialize()
}

$multibindingAnnotation
${if (injectOnClass) "@Inject\n" else ""}class ${className}InitializerImpl$index${if (injectOnClass) "" else " @Inject constructor()"} : ${className}Initializer$index {
  override fun initialize() = println("Initializing ${className.lowercase()} $index")
}
"""
      .trimIndent()
  }

  fun generateSubcomponent(module: ModuleSpec, buildMode: BuildMode): String {
    // METRO_NOOP and VANILLA don't generate subcomponents (no DI)
    if (buildMode == BuildMode.METRO_NOOP || buildMode == BuildMode.VANILLA) {
      return ""
    }

    val className = module.name.toCamelCase()

    // Only use dependencies that this module actually depends on
    val availableDependencies =
      module.dependencies
        .mapNotNull { dep ->
          val moduleName = dep.split(":").lastOrNull()?.toCamelCase()
          if (moduleName != null) "${moduleName}Api" else null
        }
        .take(2)

    val topLevelParentScopeRef =
      when (buildMode) {
        BuildMode.METRO -> "AppScope::class"
        BuildMode.DAGGER -> "Unit::class"
        BuildMode.KOTLIN_INJECT_ANVIL -> "AppScope::class"
        else -> ""
      }

    // Build hierarchy of subcomponent levels
    val levels = mutableListOf<SubcomponentLevel>()

    // L1
    levels.add(
      SubcomponentLevel(
        name = className,
        scopeName = "${className}Scope",
        parentScopeRef = topLevelParentScopeRef,
        serviceCount = 3,
        parentServiceNames = availableDependencies,
      )
    )

    // L2
    if (l2ChildrenPerL1 > 0) {
      val l1Services = (1..3).map { "${className}LocalService$it" }
      for (i in 1..l2ChildrenPerL1) {
        val l2Name = "${className}Child$i"
        levels.add(
          SubcomponentLevel(
            name = l2Name,
            scopeName = "${l2Name}Scope",
            parentScopeRef = "${className}Scope::class",
            serviceCount = 2,
            parentServiceNames = l1Services.take(2),
          )
        )

        // L3
        if (l3ChildrenPerL2 > 0) {
          val l2Services = (1..2).map { "${l2Name}LocalService$it" }
          for (j in 1..l3ChildrenPerL2) {
            val l3Name = "${l2Name}Sub$j"
            levels.add(
              SubcomponentLevel(
                name = l3Name,
                scopeName = "${l3Name}Scope",
                parentScopeRef = "${l2Name}Scope::class",
                serviceCount = 1,
                parentServiceNames = l2Services.take(1),
              )
            )
          }
        }
      }
    }

    return levels.joinToString("\n\n") { level ->
      generateSubcomponentLevel(level, availableDependencies, buildMode)
    }
  }

  fun generateSubcomponentLevel(
    level: SubcomponentLevel,
    topLevelParentDeps: List<String>,
    buildMode: BuildMode,
  ): String {
    val (name, scopeName, parentScopeRef, serviceCount, parentServiceNames) = level

    val injectOnClass = buildMode != BuildMode.DAGGER

    val scopeOnClass =
      when (buildMode) {
        BuildMode.METRO -> "@SingleIn($scopeName::class)"
        BuildMode.DAGGER -> "@$scopeName"
        BuildMode.KOTLIN_INJECT_ANVIL -> "@$scopeName"
        else -> ""
      }

    // Generate services with parent dependencies injected
    val services =
      (1..serviceCount).joinToString("\n\n") { i ->
        val dependencyParams =
          if (parentServiceNames.isNotEmpty()) {
            parentServiceNames.joinToString(",\n  ") { "private val $it: $it" }
          } else ""

        """interface ${name}LocalService$i

$scopeOnClass
@ContributesBinding($scopeName::class)
${if (injectOnClass) "@Inject\n" else ""}class ${name}LocalServiceImpl$i${if (!injectOnClass) " @Inject constructor" else ""}(${if (dependencyParams.isNotEmpty()) "\n  $dependencyParams\n" else ""}) : ${name}LocalService$i"""
      }

    // Accessors for this level's services
    val accessors =
      (1..serviceCount).joinToString("\n") { i ->
        "  fun get${name}LocalService$i(): ${name}LocalService$i"
      }

    // Only show parent scope accessors for L1 (where parents are *Api types from other modules)
    val isTopLevel = parentServiceNames.any { it.endsWith("Api") }
    val parentAccessorsSection =
      if (isTopLevel && topLevelParentDeps.isNotEmpty()) {
        val parentAccessors = topLevelParentDeps.joinToString("\n") { "  fun get$it(): $it" }
        "  // Access parent scope bindings\n$parentAccessors\n\n  // Access subcomponent scope bindings\n"
      } else ""

    return when (buildMode) {
      BuildMode.METRO ->
        """
// $name subcomponent-scoped services
$services

@SingleIn($scopeName::class)
@GraphExtension($scopeName::class)
interface ${name}Subcomponent {
$parentAccessorsSection$accessors

  @ContributesTo($parentScopeRef)
  @GraphExtension.Factory
  interface Factory {
    fun create${name}Subcomponent(): ${name}Subcomponent
  }
}

object $scopeName
"""
          .trimIndent()

      BuildMode.KOTLIN_INJECT_ANVIL ->
        """
// $name subcomponent-scoped services
$services

@$scopeName
@ContributesSubcomponent(
  scope = $scopeName::class
)
interface ${name}Subcomponent {
$parentAccessorsSection$accessors

  @ContributesSubcomponent.Factory($parentScopeRef)
  interface Factory {
    fun create${name}Subcomponent(): ${name}Subcomponent
  }
}

@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class $scopeName
"""
          .trimIndent()

      BuildMode.DAGGER ->
        """
// $name subcomponent-scoped services
$services

@$scopeName
@ContributesSubcomponent(
  scope = $scopeName::class,
  parentScope = $parentScopeRef
)
interface ${name}Subcomponent {
$parentAccessorsSection$accessors

  @ContributesTo($parentScopeRef)
  interface Factory {
    fun create${name}Subcomponent(): ${name}Subcomponent
  }
}

@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class $scopeName
"""
          .trimIndent()

      else -> error("Unsupported build mode for subcomponents: $buildMode")
    }
  }

  fun generateVanillaSubcomponentHierarchy(className: String): String {
    val parts = mutableListOf<String>()

    // L1
    parts.add(generateVanillaSingleLevel(className, 3))

    // L2
    for (i in 1..l2ChildrenPerL1) {
      val l2Name = "${className}Child$i"
      parts.add(generateVanillaSingleLevel(l2Name, 2))

      // L3
      for (j in 1..l3ChildrenPerL2) {
        val l3Name = "${l2Name}Sub$j"
        parts.add(generateVanillaSingleLevel(l3Name, 1))
      }
    }

    return parts.joinToString("\n\n")
  }

  fun generateVanillaSingleLevel(name: String, serviceCount: Int): String {
    val services =
      (1..serviceCount).joinToString("\n\n") { i ->
        """interface ${name}LocalService$i

class ${name}LocalServiceImpl$i : ${name}LocalService$i"""
      }

    val accessors =
      (1..serviceCount).joinToString("\n") { i ->
        "  fun get${name}LocalService$i(): ${name}LocalService$i"
      }

    return """// $name subcomponent-equivalent (no DI)
$services

interface ${name}Subcomponent {
$accessors

  interface Factory {
    fun create${name}Subcomponent(): ${name}Subcomponent
  }
}

object ${name}Scope"""
  }

  fun generateKoinSourceCode(module: ModuleSpec, packageName: String, className: String): String {
    // Koin path: each contribution becomes an @Singleton (or @Factory) annotated class.
    // - Simple binding: @Singleton(binds = [ServiceN::class])
    // - Plugin multibinding: @Singleton(binds = [Plugin::class])
    //   (consumer uses koin.getAll<Plugin>().toSet())
    // - Initializer multibinding: @Singleton(binds = [Initializer::class])
    //
    // Each Gradle module also emits a `@Module @ComponentScan("<its-own-pkg>")` class so the
    // Koin compiler plugin generates a module lambda per Gradle module. Without this split, a
    // single root `@ComponentScan("dev.zacsweers.metro.benchmark")` causes the plugin to emit
    // one giant lambda that overflows the JVM 64KB method size limit at ~100 modules.
    //
    // Subcomponents are emitted in Phase B as flat @Scope/@Scoped classes.
    val contributions =
      (1..module.contributionsCount)
        .map { i ->
          val moduleRandom = Random(module.name.hashCode() + i)
          when (moduleRandom.nextInt(3)) {
            0 ->
              """interface ${className}Service$i

@Singleton(binds = [${className}Service$i::class])
class ${className}ServiceImpl$i : ${className}Service$i"""
            1 ->
              """interface ${className}Plugin$i : Plugin {
  override fun execute(): String
}

@Singleton(binds = [Plugin::class])
class ${className}PluginImpl$i : ${className}Plugin$i {
  override fun execute() = "${className.lowercase()}-plugin-$i"
}"""
            else ->
              """interface ${className}Initializer$i : Initializer {
  override fun initialize()
}

@Singleton(binds = [Initializer::class])
class ${className}InitializerImpl$i : ${className}Initializer$i {
  override fun initialize() = println("Initializing ${className.lowercase()} $i")
}"""
          }
        }
        .joinToString("\n\n")

    val subcomponent =
      if (module.hasSubcomponent) {
        generateKoinSubcomponent(module, className)
      } else ""

    val subcomponentImports =
      if (module.hasSubcomponent) {
        """
import org.koin.core.Koin
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.inject"""
      } else ""

    return """
package $packageName

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton$subcomponentImports
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer

/**
 * Per-Gradle-module Koin module. `@ComponentScan` narrows to this package so the Koin compiler
 * plugin generates a small per-module registration lambda. The app's `@KoinApplication` class
 * enumerates all of these in its `modules = [...]` list.
 */
@Module
@ComponentScan("$packageName")
class ${className}KoinModule

// Main module interface
interface ${className}Api

// Implementation
@Singleton(binds = [${className}Api::class])
class ${className}Impl : ${className}Api

$contributions
$subcomponent
"""
      .trimIndent()
  }

  /**
   * Fully-qualified class reference for a generated module's KoinModule. Used by the app
   * component's `@KoinApplication(modules = [...])` list.
   */
  fun koinModuleClassFqn(module: ModuleSpec): String {
    val pkg = "dev.zacsweers.metro.benchmark.${module.layer.path}.${module.name.replace("-", "")}"
    val cn = module.name.toCamelCase()
    return "$pkg.${cn}KoinModule"
  }

  /**
   * Koin has no nested subcomponents. Emulates the Metro/Dagger/kotlin-inject L1/L2/L3 hierarchy
   * with **flat Koin scopes** (not nested containers). Each level gets:
   * - a scope marker `class <Level>Scope`
   * - per-service `@Scope(<Marker>::class) @Scoped(binds = [<Iface>::class])` impl classes
   * - a `class <Level>Subcomponent(koin: Koin) : KoinScopeComponent` wrapper that exposes the
   *   scoped services via `by scope.inject()` — mirrors the Subcomponent/Factory interface pairs
   *   that Metro/Dagger/kotlin-inject emit.
   *
   * Like the other frameworks' subcomponents, these wrappers aren't instantiated from
   * `createAndInitialize()` — they exist for compile-time stress. The KoinScopeComponent structure
   * is modelled on the KotlinConf app's migration pattern (`class YearScope(...) :
   * KoinScopeComponent { override val scope = ...; val storage by scope.inject() }`).
   *
   * This is a real property of Koin (scopes are flat under the root, not nested containers), not a
   * benchmark fudge; the README calls this out.
   */
  fun generateKoinSubcomponent(module: ModuleSpec, className: String): String {
    data class Level(val name: String, val serviceCount: Int)
    val levels = mutableListOf<Level>()
    levels += Level(className, 3) // L1

    if (l2ChildrenPerL1 > 0) {
      for (i in 1..l2ChildrenPerL1) {
        val l2Name = "${className}Child$i"
        levels += Level(l2Name, 2)
        if (l3ChildrenPerL2 > 0) {
          for (j in 1..l3ChildrenPerL2) {
            levels += Level("${l2Name}Sub$j", 1)
          }
        }
      }
    }

    return levels.joinToString("\n\n") { (levelName, serviceCount) ->
      val scopeMarker = "${levelName}Scope"
      val services =
        (1..serviceCount).joinToString("\n\n") { i ->
          """interface ${levelName}LocalService$i

@Scope($scopeMarker::class)
@Scoped(binds = [${levelName}LocalService$i::class])
class ${levelName}LocalServiceImpl$i : ${levelName}LocalService$i"""
        }
      val scopeProperties =
        (1..serviceCount).joinToString("\n  ") { i ->
          "val localService$i: ${levelName}LocalService$i by scope.inject()"
        }
      """
// $levelName flat Koin scope (Koin has no nested subcomponents — flat scopes only).
// The KoinScopeComponent wrapper mirrors the subcomponent+Factory pair the other frameworks
// emit; it is not instantiated from createAndInitialize() (parity with Metro/Dagger).
class $scopeMarker

$services

class ${levelName}Subcomponent(private val parentKoin: Koin, scopeId: String) :
  KoinScopeComponent {
  override fun getKoin(): Koin = parentKoin
  override val scope: org.koin.core.scope.Scope =
    parentKoin.getOrCreateScope(
      scopeId,
      org.koin.core.qualifier.TypeQualifier($scopeMarker::class),
    )
  $scopeProperties
  fun close() = scope.close()
}
"""
        .trimIndent()
    }
  }

  fun generateFoundationModule(multiplatform: Boolean) {
    val foundationDir = File("core/foundation")
    foundationDir.mkdirs()

    // Create build.gradle.kts
    val buildFile = File(foundationDir, "build.gradle.kts")
    val buildScript =
      if (multiplatform) {
        """
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

val enableMacos = providers.gradleProperty("benchmark.native.macos").orNull.toBoolean()
val enableLinux = providers.gradleProperty("benchmark.native.linux").orNull.toBoolean()
val enableWindows = providers.gradleProperty("benchmark.native.windows").orNull.toBoolean()

kotlin {
  jvm()
  js(IR) { nodejs() }
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs { nodejs() }
  if (enableMacos) {
    macosArm64()
    macosX64()
  } else if (enableLinux) {
    linuxX64()
  } else if (enableWindows) {
    mingwX64()
  }
}
"""
      } else {
        """
plugins {
  alias(libs.plugins.kotlin.jvm)
}
"""
      }
    buildFile.writeText(buildScript.trimIndent())

    // Create source directory
    val srcPath = if (multiplatform) "src/commonMain/kotlin" else "src/main/kotlin"
    val srcDir = File(foundationDir, "$srcPath/dev/zacsweers/metro/benchmark/core/foundation")
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
      $$"""
package dev.zacsweers.metro.benchmark.core.foundation

/**
 * A simple plain Kotlin class without any dependency injection annotations.
 * Used for benchmarking compiler plugin overhead on non-DI files.
 */
class PlainDataProcessor {
  private var counter = 0

  fun processData(input: String): String {
    counter++
    return "Processed: $input (#$counter)"
  }

  fun getProcessedCount(): Int {
    return counter
  }
}
"""
    plainFile.writeText(plainSourceCode.trimIndent())
  }

  fun metroDsl(): String {
    val options =
      mutableListOf<String>().apply {
        if (enableSharding) add("  enableGraphSharding.set(true)")
        if (enableSwitchingProviders) add("  enableSwitchingProviders.set(true)")
        if (parallelThreads > 0) add("  parallelThreads.set($parallelThreads)")
        if (enableReports)
          add("  reportsDestination.set(layout.buildDirectory.dir(\"metro-reports\"))")
        if (enableTracing) add("  traceDestination.set(layout.buildDirectory.dir(\"metro-trace\"))")
      }
    return if (options.isEmpty()) {
      ""
    } else {
      options.add(0, "metro {")
      options.add(
        0,
        "@OptIn(dev.zacsweers.metro.gradle.DelicateMetroGradleApi::class, dev.zacsweers.metro.gradle.DangerousMetroGradleApi::class)",
      )
      options.add("}")
      options.joinToString("\n")
    }
  }

  fun generateAppComponent(allModules: List<ModuleSpec>, processor: ProcessorMode) {
    val appDir = File("app/component")
    appDir.mkdirs()

    val buildFile = File(appDir, "build.gradle.kts")
    val moduleDepsCommon =
      allModules.joinToString("\n") {
        "        implementation(project(\":${it.layer.path}:${it.name}\"))"
      }
    val moduleDepsJvm =
      allModules.joinToString("\n") {
        "  implementation(project(\":${it.layer.path}:${it.name}\"))"
      }

    val buildScript =
      when (buildMode) {
        BuildMode.METRO ->
          if (multiplatform) {
            """
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("dev.zacsweers.metro")
}

val enableMacos = providers.gradleProperty("benchmark.native.macos").orNull.toBoolean()
val enableLinux = providers.gradleProperty("benchmark.native.linux").orNull.toBoolean()
val enableWindows = providers.gradleProperty("benchmark.native.windows").orNull.toBoolean()
${metroDsl()}
kotlin {
  jvm()
  js(IR) {
    nodejs()
    binaries.executable()
  }
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    nodejs()
    binaries.executable()
  }
  if (enableMacos) {
    macosArm64 { binaries.executable() }
    macosX64 { binaries.executable() }
  } else if (enableLinux) {
    linuxX64 { binaries.executable() }
  } else if (enableWindows) {
    mingwX64 { binaries.executable() }
  }

  sourceSets {
    commonMain {
      dependencies {
        implementation("dev.zacsweers.metro:runtime:+")
        implementation(project(":core:foundation"))

        // Depend on all generated modules to aggregate everything
$moduleDepsCommon
      }
    }
  }
}
"""
          } else {
            """
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.zacsweers.metro")
  application
}
${metroDsl()}
dependencies {
  implementation("dev.zacsweers.metro:runtime:+")
  implementation(project(":core:foundation"))

  // Depend on all generated modules to aggregate everything
$moduleDepsJvm
}

application {
  mainClass = "dev.zacsweers.metro.benchmark.app.component.AppComponentKt"
}
"""
          }

        BuildMode.METRO_NOOP ->
          """
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.zacsweers.metro")
  id("dev.zacsweers.metro")
  application
}

dependencies {
  implementation(project(":core:foundation"))

  // Depend on all generated modules to aggregate everything
${allModules.joinToString("\n") { "  implementation(project(\":${it.layer.path}:${it.name}\"))" }}
}

application {
  mainClass = "dev.zacsweers.metro.benchmark.app.component.AppComponentKt"
}
"""

        BuildMode.VANILLA ->
          """
plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

dependencies {
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
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
  application
}

dependencies {
  implementation(libs.kotlinInject.runtime)
  implementation(libs.kotlinInject.anvil.runtime)
  implementation(libs.kotlinInject.anvil.runtime.optional)
  implementation(project(":core:foundation"))
  ksp(libs.kotlinInject.compiler)
  ksp(libs.kotlinInject.anvil.compiler)

  // Depend on all generated modules to aggregate everything
${allModules.joinToString("\n") { "  implementation(project(\":${it.layer.path}:${it.name}\"))" }}
}

application {
  mainClass = "dev.zacsweers.metro.benchmark.app.component.AppComponentKt"
}
"""

        BuildMode.KOIN ->
          if (multiplatform) {
            """
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.koin.compiler)
}

val enableMacos = providers.gradleProperty("benchmark.native.macos").orNull.toBoolean()
val enableLinux = providers.gradleProperty("benchmark.native.linux").orNull.toBoolean()
val enableWindows = providers.gradleProperty("benchmark.native.windows").orNull.toBoolean()

kotlin {
  jvm()
  js(IR) {
    nodejs()
    binaries.executable()
  }
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    nodejs()
    binaries.executable()
  }
  if (enableMacos) {
    macosArm64 { binaries.executable() }
    macosX64 { binaries.executable() }
  } else if (enableLinux) {
    linuxX64 { binaries.executable() }
  } else if (enableWindows) {
    mingwX64 { binaries.executable() }
  }

  sourceSets {
    commonMain {
      dependencies {
        implementation(libs.koin.core)
        implementation(libs.koin.annotations)
        implementation(project(":core:foundation"))

        // Depend on all generated modules to aggregate everything
$moduleDepsCommon
      }
    }
  }
}
"""
          } else {
            """
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.koin.compiler)
  application
}

dependencies {
  implementation(libs.koin.core)
  implementation(libs.koin.annotations)
  implementation(project(":core:foundation"))

  // Depend on all generated modules to aggregate everything
${allModules.joinToString("\n") { "  implementation(project(\":${it.layer.path}:${it.name}\"))" }}
}

application {
  mainClass = "dev.zacsweers.metro.benchmark.app.component.AppComponentKt"
}
"""
          }

        BuildMode.DAGGER ->
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
  implementation(libs.javaxInject)
  implementation(libs.anvil.annotations)
  implementation(libs.dagger.runtime)
  implementation(project(":core:foundation"))
  ksp(libs.anvil.kspCompiler)
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
${daggerKspFastInit()}
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
  implementation(libs.javaxInject)
  implementation(libs.anvil.annotations)
  implementation(libs.dagger.runtime)
  implementation(project(":core:foundation"))
  ksp(libs.anvil.kspCompiler)
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
${daggerKaptFastInit()}
application {
  mainClass = "dev.zacsweers.metro.benchmark.app.component.AppComponentKt"
}
"""
          }
      }

    buildFile.writeText(buildScript.trimIndent())

    val srcPath =
      if (multiplatform && (buildMode == BuildMode.METRO || buildMode == BuildMode.KOIN))
        "src/commonMain/kotlin"
      else "src/main/kotlin"
    val srcDir = File(appDir, "$srcPath/dev/zacsweers/metro/benchmark/app/component")
    srcDir.mkdirs()

    val sourceFile = File(srcDir, "AppComponent.kt")

    // Metro uses the function-syntax provider form `() -> T` (no import needed). Dagger/NOOP use
    // `javax.inject.Provider`.
    val providerImport =
      when {
        !providerMultibindings -> ""
        buildMode == BuildMode.METRO -> ""
        buildMode == BuildMode.DAGGER -> "import javax.inject.Provider"
        else -> "import javax.inject.Provider" // NOOP uses javax style for consistency
      }

    // Multibinding types based on providerMultibindings flag
    val pluginsType =
      when {
        !providerMultibindings -> "Set<Plugin>"
        buildMode == BuildMode.METRO -> "() -> Set<Plugin>"
        else -> "Provider<Set<Plugin>>"
      }
    val initializersType =
      when {
        !providerMultibindings -> "Set<Initializer>"
        buildMode == BuildMode.METRO -> "() -> Set<Initializer>"
        else -> "Provider<Set<Initializer>>"
      }

    // Access pattern for multibindings - Metro uses operator invoke, Dagger uses .get()
    val pluginsAccess =
      when {
        !providerMultibindings -> "graph.getAllPlugins()"
        buildMode == BuildMode.METRO ->
          "graph.getAllPlugins()()" // function invocation on () -> Set<Plugin>
        else -> "graph.getAllPlugins().get()" // Dagger/javax Provider uses .get()
      }
    val initializersAccess =
      when {
        !providerMultibindings -> "graph.getAllInitializers()"
        buildMode == BuildMode.METRO ->
          "graph.getAllInitializers()()" // function invocation on () -> Set<Initializer>
        else -> "graph.getAllInitializers().get()" // Dagger/javax Provider uses .get()
      }

    val metroMainFunction =
      if (multiplatform) {
        // Multiplatform-compatible main (no javaClass)
        $$"""
fun main() {
  val graph = createAndInitialize()
  val plugins = $$pluginsAccess
  val initializers = $$initializersAccess

  println("Metro benchmark graph successfully created!")
  println("  - Plugins: ${plugins.size}")
  println("  - Initializers: ${initializers.size}")
  println("  - Total modules: $${allModules.size}")
  println("  - Total contributions: $${allModules.sumOf { it.contributionsCount }}")
}
"""
      } else {
        // JVM-only main with reflection
        $$"""
fun main() {
  val graph = createAndInitialize()
  val fields = graph.javaClass.declaredFields.size
  val methods = graph.javaClass.declaredMethods.size
  val plugins = $$pluginsAccess
  val initializers = $$initializersAccess

  println("Metro benchmark graph successfully created!")
  println("  - Fields: $fields")
  println("  - Methods: $methods")
  println("  - Plugins: ${plugins.size}")
  println("  - Initializers: ${initializers.size}")
  println("  - Total modules: $${allModules.size}")
  println("  - Total contributions: $${allModules.sumOf { it.contributionsCount }}")
}
"""
      }

    val sourceCode =
      when (buildMode) {
        BuildMode.METRO ->
          $$"""
package dev.zacsweers.metro.benchmark.app.component

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer
$${if (providerImport.isNotEmpty()) "$providerImport\n" else ""}
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppComponent {
  // Multibinding accessors
  fun getAllPlugins(): $$pluginsType
  fun getAllInitializers(): $$initializersType

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
  $$pluginsAccess
  $$initializersAccess
  return graph
}
$$metroMainFunction
"""

        BuildMode.METRO_NOOP,
        BuildMode.VANILLA -> {
          val modeDescription =
            if (buildMode == BuildMode.METRO_NOOP)
              "METRO_NOOP mode - Metro compiler plugin is applied but no Metro annotations are used."
            else "VANILLA mode - Pure Kotlin with no DI framework."
          """
package dev.zacsweers.metro.benchmark.app.component

import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer

/**
 * $modeDescription
 * This is a baseline to measure compilation overhead.
 */
interface AppComponent

fun main() {
  println("${buildMode.name} benchmark completed!")
  println("  - Total modules: ${allModules.size}")
  println("  - Total contributions: ${allModules.sumOf { it.contributionsCount }}")
  println("  - This is a baseline measurement for Kotlin compilation")
}
"""
        }

        BuildMode.KOTLIN_INJECT_ANVIL ->
          $$"""
package dev.zacsweers.metro.benchmark.app.component

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer

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
  println("  - Fields: $fields")
  println("  - Methods: $methods")
  println("  - Plugins: ${plugins.size}")
  println("  - Initializers: ${initializers.size}")
  println("  - Total modules: $${allModules.size}")
  println("  - Total contributions: $${allModules.sumOf { it.contributionsCount }}")
}
"""

        BuildMode.KOIN -> {
          // Koin has no multibindings; synthesize Set<T> via koin.getAll<T>().toSet().
          //
          // We use the typed `koinApplication<AppKoinApp> { }` (not `startKoin<AppKoinApp>`) so
          // each benchmark iteration builds a fresh, detached KoinApplication with no global
          // state — important for JMH where the method is called repeatedly.
          //
          // The Koin compiler plugin (io.insert-koin.compiler.plugin) generates the typed
          // `koinApplication<T>` extension for any `@KoinApplication`-annotated class, wiring
          // in modules enumerated in `@KoinApplication(modules = [...])`. We cannot use a single
          // root `@ComponentScan` on AppKoinApp because Koin's plugin emits all registrations
          // into one lambda and the JVM 64KB method limit overflows at ~100 modules; instead,
          // each generated Gradle module has its own `@Module @ComponentScan(<its-pkg>)` class
          // and we list them all here.
          //
          // Phase C note: Koin + Kotlin Multiplatform is not supported here (Koin compiler
          // plugin 1.0.0-RC2 conflicts with KMP over LifecycleBasePlugin registration).
          val koinModuleImports =
            allModules.joinToString("\n") { "import ${koinModuleClassFqn(it)}" }
          val koinModuleClassLiterals =
            allModules.joinToString(",\n    ") { "${it.name.toCamelCase()}KoinModule::class" }
          val koinMainFunction =
            if (multiplatform) {
              $$"""
fun main() {
  val component = createAndInitialize()
  val plugins = component.getAllPlugins()
  val initializers = component.getAllInitializers()

  println("Koin benchmark graph successfully created!")
  println("  - Plugins: ${plugins.size}")
  println("  - Initializers: ${initializers.size}")
  println("  - Total modules: $${allModules.size}")
  println("  - Total contributions: $${allModules.sumOf { it.contributionsCount }}")
}
"""
            } else {
              $$"""
fun main() {
  val component = createAndInitialize()
  val fields = component.javaClass.declaredFields.size
  val methods = component.javaClass.declaredMethods.size
  val plugins = component.getAllPlugins()
  val initializers = component.getAllInitializers()

  println("Koin benchmark graph successfully created!")
  println("  - Fields: $fields")
  println("  - Methods: $methods")
  println("  - Plugins: ${plugins.size}")
  println("  - Initializers: ${initializers.size}")
  println("  - Total modules: $${allModules.size}")
  println("  - Total contributions: $${allModules.sumOf { it.contributionsCount }}")
}
"""
            }
          $$"""
package dev.zacsweers.metro.benchmark.app.component

import dev.zacsweers.metro.benchmark.core.foundation.Initializer
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import org.koin.core.Koin
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.koinApplication
$$koinModuleImports

/**
 * Root Koin application. `@KoinApplication(modules = [...])` enumerates every per-Gradle-module
 * `*KoinModule` class; each of those has a narrow `@ComponentScan` so the compiler plugin
 * generates one small registration lambda per module. This avoids the 64KB JVM method-size
 * overflow that a single root `@ComponentScan` would cause at benchmark scale.
 *
 * We make no claims about what compile-time validation the Koin plugin performs — the benchmark
 * measures runtime cost and build-time cost as observed, not correctness guarantees.
 */
@KoinApplication(
  modules = [
    $$koinModuleClassLiterals,
  ],
)
class AppKoinApp

/**
 * Facade that exposes multibindings in the same shape as the other frameworks' AppComponent.
 * Koin has no native multibindings, so `Set<Plugin>` / `Set<Initializer>` are synthesized via
 * [Koin.getAll] + `.toSet()`. This wrapping adds a small allocation; it is inherent to the
 * functional-equivalence mapping, not a benchmark artifact.
 */
class AppComponent(val koin: Koin) {
  fun getAllPlugins(): Set<Plugin> = koin.getAll<Plugin>().toSet()
  fun getAllInitializers(): Set<Initializer> = koin.getAll<Initializer>().toSet()
}

/**
 * Creates and fully initializes the dependency graph.
 * Primary entry point for benchmarking graph creation and initialization.
 */
fun createAndInitialize(): AppComponent {
  val app = koinApplication<AppKoinApp> { }
  val component = AppComponent(app.koin)
  // Force realization of multibinding contributors (parity with the other modes).
  component.getAllPlugins()
  component.getAllInitializers()
  return component
}
$$koinMainFunction
"""
        }

        BuildMode.DAGGER -> {
          // Dagger uses component variable name instead of graph
          val daggerPluginsAccess =
            if (providerMultibindings) "component.getAllPlugins().get()"
            else "component.getAllPlugins()"
          val daggerInitializersAccess =
            if (providerMultibindings) "component.getAllInitializers().get()"
            else "component.getAllInitializers()"
          $$"""
package dev.zacsweers.metro.benchmark.app.component

import com.squareup.anvil.annotations.MergeComponent
import com.squareup.anvil.annotations.ContributesTo
import javax.inject.Singleton
$${if (providerImport.isNotEmpty()) "$providerImport\n" else ""}import dagger.multibindings.Multibinds
import dev.zacsweers.metro.benchmark.core.foundation.Plugin
import dev.zacsweers.metro.benchmark.core.foundation.Initializer

@Singleton
@MergeComponent(Unit::class)
interface AppComponent {
  // Multibinding accessors
  fun getAllPlugins(): $$pluginsType
  fun getAllInitializers(): $$initializersType

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
  val component = DaggerAppComponent.factory().create()
  // Force full initialization by accessing all multibindings
  $$daggerPluginsAccess
  $$daggerInitializersAccess
  return component
}

fun main() {
  val component = createAndInitialize()
  val fields = component.javaClass.declaredFields.size
  val methods = component.javaClass.declaredMethods.size
  val plugins = $$daggerPluginsAccess
  val initializers = $$daggerInitializersAccess

  println("Anvil benchmark graph successfully created!")
  println("  - Fields: $fields")
  println("  - Methods: $methods")
  println("  - Plugins: ${plugins.size}")
  println("  - Initializers: ${initializers.size}")
  println("  - Total modules: $${allModules.size}")
  println("  - Total contributions: $${allModules.sumOf { it.contributionsCount }}")
}
"""
        }
      }

    sourceFile.writeText(sourceCode.trimIndent())
  }

  fun writeSettingsFile(allModules: List<ModuleSpec>) {
    val settingsFile = File("generated-projects.txt")
    val includes = buildList {
      add("# multiplatform: $multiplatform")
      add(":core:foundation")
      addAll(allModules.map { ":${it.layer.path}:${it.name}" })
      add(":app:component")
    }
    val content = includes.joinToString("\n")
    settingsFile.writeText(content)
  }
}

// Execute the command
GenerateProjectsCommand().main(args)
