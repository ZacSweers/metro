// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.GradleBuilder.buildAndFail
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.Source
import com.autonomousapps.kit.Subproject
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.KotlinToolingVersion
import dev.zacsweers.metro.gradle.copy
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import dev.zacsweers.metro.gradle.getTestCompilerVersion
import dev.zacsweers.metro.gradle.resolveSafe
import java.io.File
import org.intellij.lang.annotations.Language
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before

private const val GRADLE_DEBUG_ARGS = "-Dorg.gradle.debug=true"
private const val KOTLIN_DEBUG_ARGS =
  """-Dkotlin.daemon.jvm.options="-agentlib:jdwp=transport=dt_socket\,server=n\,suspend=y\,address=5005""""

/** Minimum Kotlin version that supports incremental compilation for KMP projects. */
private val MULTIPLATFORM_IC_MIN_VERSION = KotlinToolingVersion("2.3.21")

/**
 * Kotlin/JS and Kotlin/Wasm IC trip on top-level declaration generation in these specific Kotlin
 * builds (Metro uses top-level declarations by default for `enableTopLevelFunctionInjection` /
 * `generateContributionHints` / `generateContributionHintsInFir`).
 *
 * See https://youtrack.jetbrains.com/issue/KT-82395 and
 * https://youtrack.jetbrains.com/issue/KT-82989.
 */
private val JS_WASM_IC_TOP_LEVEL_BROKEN_VERSIONS = setOf("2.4.0-Beta1", "2.4.0-dev-2124")

abstract class BaseIncrementalCompilationTest(protected val target: KmpTarget) {

  @Before
  fun assumeMultiplatformIcSupported() {
    assumeTrue(
      "KMP incremental compilation requires Kotlin $MULTIPLATFORM_IC_MIN_VERSION+",
      getTestCompilerToolingVersion() >= MULTIPLATFORM_IC_MIN_VERSION,
    )
  }

  @Before
  fun assumeJsAndWasmTopLevelDeclarationsSupported() {
    if (target != KmpTarget.JS && target != KmpTarget.WASM_JS) return
    assumeFalse(
      "Kotlin/$target IC cannot generate top-level declarations on " +
        "${getTestCompilerVersion()} (KT-82395, KT-82989)",
      getTestCompilerVersion() in JS_WASM_IC_TOP_LEVEL_BROKEN_VERSIONS,
    )
  }

  /** Compile task name for the [target]'s main compilation, e.g. `compileKotlinJvm`. */
  protected val targetCompileTaskName: String
    get() = target.compileTaskName

  /**
   * Returns the fully-qualified Gradle task path for the current [target]'s compile task. Pass an
   * empty [projectPath] for the root project, or a name like `"lib"` / `":lib"` for a subproject.
   */
  protected fun compileTaskFor(projectPath: String = ""): String {
    val normalized = projectPath.trim(':')
    return if (normalized.isEmpty()) {
      ":$targetCompileTaskName"
    } else {
      ":$normalized:$targetCompileTaskName"
    }
  }

  /** Runs [block] only when the current parameter [target] is [KmpTarget.JVM]. */
  protected inline fun ifJvmTarget(block: () -> Unit) {
    if (target == KmpTarget.JVM) block()
  }

  protected val GradleProject.asMetroProject: MetroGradleProject
    get() = MetroGradleProject(rootDir)

  protected fun GradleProject.metroProject(path: String): MetroGradleProject {
    return MetroGradleProject(rootDir.resolve(path))
  }

  @JvmInline protected value class MetroGradleProject(val rootDir: File)

  protected val MetroGradleProject.buildDir: File
    get() = rootDir.resolve("build")

  protected val MetroGradleProject.metroDir: File
    get() = buildDir.resolve("metro")

  protected fun MetroGradleProject.reports(compilation: String): Reports =
    metroDir.resolveSafe(compilation).let(::Reports)

  // Metro's reports layout is `{reportsDestination}/{targetName}/{compilationName}/`, so the
  // current parameterized target picks the `<target>/main` slice.
  protected val MetroGradleProject.mainReports: Reports
    get() = reports("${target.gradleTargetName}/main")

  protected val MetroGradleProject.appGraphReports: GraphReports
    get() = mainReports.forGraph("test/AppGraph/Impl")

  class Reports(val dir: File) {
    val expectActualReports
      get() = dir.resolveSafe("expectActualReports.csv").readText()

    val lookups
      get() = dir.resolveSafe("lookups.csv").readText()

    val log
      get() = dir.resolveSafe("log.txt").readText()

    val trace
      get() = dir.resolveSafe("trace").listFiles().single()

    fun irHintsForScope(scopeFqName: String): String {
      return dir.resolveSafe("discovered-hints-ir/$scopeFqName.txt").readText()
    }

    fun firHintsForScope(scopeFqName: String): String {
      return dir.resolveSafe("discovered-hints-fir/$scopeFqName.txt").readText()
    }

    fun unmatchedExclusionsIr(scopeFqName: String): String {
      return dir.resolveSafe("merging-unmatched-exclusions-ir/$scopeFqName.txt").readText()
    }

    fun unmatchedReplacementsIr(scopeFqName: String): String {
      return dir.resolveSafe("merging-unmatched-replacements-ir/$scopeFqName.txt").readText()
    }

    fun unmatchedRankReplacementsIr(scopeFqName: String): String {
      return dir.resolveSafe("merging-unmatched-rank-replacements-ir/$scopeFqName.txt").readText()
    }

    fun forGraph(implFqName: String): GraphReports {
      return GraphReports(dir, implFqName)
    }
  }

  // TODO shared model?
  class GraphReports(val reportsDir: File, val implFqName: String) {
    private fun readFileLines(path: String, extension: String = "txt"): List<String> {
      return reportsDir.resolveSafe("$path.$extension").readLines()
    }

    private fun readFile(pathWithExtension: String): String {
      return reportsDir.resolveSafe(pathWithExtension).readText()
    }

    val keysPopulated
      get() = readFileLines("keys-populated/$implFqName")

    val providerPropertyKeys
      get() = readFileLines("keys-providerProperties/$implFqName")

    val scopedProviderPropertyKeys
      get() = readFileLines("keys-scopedProviderProperties/$implFqName")

    val deferred
      get() = readFileLines("keys-deferred/$implFqName")

    val dumpKotlinLike
      get() = readFile("graph-dumpKotlin/$implFqName.kt")

    val dump
      get() = readFileLines("graph-dump/$implFqName")

    val bindingContainers
      get() = readFileLines("graph-dump/$implFqName")

    val keysValidated
      get() = readFileLines("keys-validated/$implFqName")

    val keysUnused
      get() = readFileLines("keys-unused/$implFqName")

    val metadata
      get() = readFile("graph-metadata/$implFqName.kt")

    val parentUsedKeysAll
      get() = readFile("parent-keys-used-all/$implFqName")

    fun parentKeysUsedBy(extension: String) =
      readFileLines("parent-keys-used/$implFqName-by-$extension.txt")

    fun graphMetadata() {
      // /graph-metadata/graph-test-AppGraph.json"
      // /graph-metadata/graph-test-AppGraph2.json"
      TODO()
    }

    fun unmatchedExclusionsFir(graphFqName: String): String {
      return reportsDir.resolveSafe("merging-unmatched-exclusions-fir/$graphFqName.txt").readText()
    }

    fun unmatchedReplacementsFir(graphFqName: String): String {
      return reportsDir
        .resolveSafe("merging-unmatched-replacements-fir/$graphFqName.txt")
        .readText()
    }

    fun unmatchedRankReplacementsFir(graphFqName: String): String {
      return reportsDir
        .resolveSafe("merging-unmatched-rank-replacements-fir/$graphFqName.txt")
        .readText()
    }
  }

  protected fun GradleProject.delete(source: Source) {
    val filePath = "src/commonMain/kotlin/${source.path}/${source.name}.kt"
    rootDir.resolve(filePath).delete()
  }

  protected fun GradleProject.modify(source: Source, @Language("kotlin") content: String) {
    val newSource = source.copy(content)
    val filePath = "src/commonMain/kotlin/${newSource.path}/${newSource.name}.kt"
    rootDir.resolve(filePath).writeText(newSource.source)
  }

  protected fun Subproject.modify(
    rootDir: File,
    source: Source,
    @Language("kotlin") content: String,
    includeDefaultImports: Boolean = true,
  ) {
    val newSource = source.copy(content, includeDefaultImports)
    val filePath = "src/commonMain/kotlin/${newSource.path}/${newSource.name}.kt"
    val projectPath = rootDir.resolve(this.name.removePrefix(":").replace(":", "/"))
    projectPath.resolve(filePath).writeText(newSource.source)
  }

  protected fun Subproject.delete(rootDir: File, source: Source) {
    val filePath = "src/commonMain/kotlin/${source.path}/${source.name}.kt"
    val projectPath = rootDir.resolve(this.name.removePrefix(":").replace(":", "/"))
    projectPath.resolve(filePath).delete()
  }

  protected fun modifyKotlinFile(
    rootDir: File,
    packageName: String,
    fileName: String,
    @Language("kotlin") content: String,
  ) {
    val packageDir = packageName.replace('.', '/')
    val filePath = "src/commonMain/kotlin/$packageDir/$fileName"
    rootDir.resolve(filePath).writeText(content)
  }

  protected fun GradleProject.compileKotlin(
    task: String = compileTaskFor(),
    debug: Boolean = false,
    vararg args: String,
  ) = compileKotlin(rootDir, task, debug, *args)

  protected fun GradleProject.compileKotlinAndFail(
    task: String = compileTaskFor(),
    debug: Boolean = false,
    vararg args: String,
  ) = compileKotlinAndFail(rootDir, task, debug, *args)

  protected fun compileKotlin(
    projectDir: File,
    task: String = compileTaskFor(),
    enableDebugger: Boolean = false,
    vararg args: String,
  ) = build(projectDir, *buildArgs(task, enableDebugger, quiet = true, *args))

  protected fun compileKotlinAndFail(
    projectDir: File,
    task: String = compileTaskFor(),
    enableDebugger: Boolean = false,
    vararg args: String,
  ) = buildAndFail(projectDir, *buildArgs(task, enableDebugger, quiet = true, *args))

  private fun buildArgs(
    task: String,
    enableDebugger: Boolean,
    quiet: Boolean,
    vararg args: String,
  ): Array<String> {
    return buildList {
        add(task)
        if (enableDebugger) {
          add(GRADLE_DEBUG_ARGS)
          add(KOTLIN_DEBUG_ARGS)
        }
        if (quiet) {
          add("--quiet")
        }
        addAll(args)
      }
      .toTypedArray()
  }
}
