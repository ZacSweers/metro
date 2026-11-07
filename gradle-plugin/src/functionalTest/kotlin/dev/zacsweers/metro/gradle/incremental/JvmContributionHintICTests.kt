// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.KotlinToolingVersion
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import dev.zacsweers.metro.gradle.invokeMain
import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Exercises the same mutations with FIR hints and the opt-in JVM IR path. */
@RunWith(Parameterized::class)
class JvmContributionHintICTests(private val irHints: Boolean) :
  BaseIncrementalCompilationTest(KmpTarget.JVM, requiresMultiplatformIc = false) {

  @Before
  fun requireIrClassGeneration() {
    assumeTrue(
      "The JVM hint experiment requires IR class generation (Kotlin 2.4.20+)",
      getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.20"),
    )
  }

  @Test
  fun unrelatedEditsPreserveHintsWithoutRecompilingNativeContributions() {
    val project = ContributionsProject().gradleProject
    project.compileAndCheck("first:")
    val reportsBefore = project.buildReports()

    project.libSource("Unrelated.kt").writeText("package test\nprivate fun unrelated() = 2\n")
    val result = project.compileAndCheck("first:")
    assertThat(result.task(":lib:compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val reports = project.buildReports() - reportsBefore
    assertThat(reports).isNotEmpty()
    val sources = reports.flatMap { compiledSourceNames(it, ":lib:compileKotlin") }.toSet()
    if (irHints) {
      assertThat(sources).containsExactly("Unrelated.kt")
    } else {
      assertThat(sources).containsAtLeast("Unrelated.kt", "First.kt")
    }

    // A second incremental round detects output mistakenly removed with plugin-generated files.
    project.libSource("Unrelated.kt").writeText("package test\nprivate fun unrelated() = 3\n")
    project.compileAndCheck("first:")
    project.compileAndCheck("first:", clean = true)
  }

  // #149/#331 require scope lookup tracking for a contribution the graph has never seen.
  @Test
  fun newDeletedAndRestoredContributionsInvalidateAnExistingDownstreamGraph() {
    val project = ContributionsProject().gradleProject
    project.compileAndCheck("first:")
    project.writeContribution("Second.kt", "Second", "second")
    assertThat(project.compileAndCheck("first,second:").task(":compileKotlin")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)

    // #1348: restoration after a successful build without the contribution must be discovered.
    project.libSource("Second.kt").delete()
    project.compileAndCheck("first:")
    project.writeContribution("Restored.kt", "Second", "second")
    project.compileAndCheck("first,second:")
    project.compileAndCheck("first,second:", clean = true)
  }

  // #1393: the old scope's hint must disappear even though the class still exists.
  @Test
  fun changingScopeMovesAContributionBetweenExistingGraphs() {
    val project = ContributionsProject().gradleProject
    project.compileAndCheck("first:")
    val source = project.libSource("First.kt")
    val original = source.readText()
    source.writeText(original.replace("AppScope::class", "OtherScope::class"))
    project.compileAndCheck(":first")
    source.writeText(original.replace("@ContributesIntoSet(AppScope::class)", ""))
    project.compileAndCheck(":")
    source.writeText(original)
    project.compileAndCheck("first:")
    project.compileAndCheck("first:", clean = true)
  }

  // #1772: a failed compilation must not allow stale hints to make a later invalid graph compile.
  @Test
  fun failedScopeChangeAndAnnotationRemovalKeepReportingMissingBinding() {
    val project = ContributionsProject(requiredBinding = true).gradleProject
    project.compileAndCheck("first")
    val source = project.libSource("First.kt")
    val original = source.readText()
    source.writeText(original.replace("AppScope::class", "OtherScope::class"))
    val reportsBeforeWrongScope = project.buildReports()
    val wrongScope = project.compileKotlinAndFail(":compileKotlin", false, "-PirHints=$irHints")
    assertThat(wrongScope.output).contains("[Metro/MissingBinding]")
    project.assertIncrementalCompilation(reportsBeforeWrongScope)
    source.writeText(original.replace("@ContributesBinding(AppScope::class)", ""))
    val reportsBeforeRemoval = project.buildReports()
    val missing = project.compileKotlinAndFail(":compileKotlin", false, "-PirHints=$irHints")
    assertThat(missing.output).contains("[Metro/MissingBinding]")
    project.assertIncrementalCompilation(reportsBeforeRemoval)
    source.writeText(original)
    project.compileAndCheck("first")
    project.compileAndCheck("first", clean = true)
  }

  // #2908: removing a replacement must restore the original contribution.
  @Test
  fun replacementRemovalAndRestorationMatchCleanGraphs() {
    val project = ContributionsProject().gradleProject
    project.compileAndCheck("first:")
    project.writeContribution(
      "Replacement.kt",
      "Replacement",
      "replacement",
      "@ContributesIntoSet(AppScope::class, replaces = [First::class])",
    )
    project.compileAndCheck("replacement:")
    val replacement = project.libSource("Replacement.kt")
    val original = replacement.readText()
    replacement.writeText(
      original.replace("@ContributesIntoSet(AppScope::class, replaces = [First::class])", ""),
    )
    project.compileAndCheck("first:")
    replacement.writeText(original)
    project.compileAndCheck("replacement:")
    project.compileAndCheck("replacement:", clean = true)
  }

  @Test
  fun hiltHintsCoexistWithNativeHintsAndDeduplicateAnOverlappingContribution() {
    val project = ContributionsProject(hilt = true).gradleProject
    project.compileAndCheck("first:hilt")
    val reportsBefore = project.buildReports()
    project.libSource("Unrelated.kt").writeText("package test\nprivate fun unrelated() = 2\n")
    project.compileAndCheck("first:hilt")
    val sources =
      (project.buildReports() - reportsBefore)
        .flatMap { compiledSourceNames(it, ":lib:compileKotlin") }
        .toSet()
    assertThat(sources).contains("Unrelated.kt")
    if (irHints) {
      assertThat(sources).doesNotContain("First.kt")
    }

    val entryPoint = project.libSource("HiltAccess.kt")
    val original = entryPoint.readText()
    entryPoint.writeText(original.replace("@ContributesTo(javax.inject.Singleton::class)", ""))
    project.compileAndCheck("first:hilt")
    entryPoint.writeText(original.replace("@dagger.hilt.EntryPoint", ""))
    project.compileAndCheck("first:hilt")
    project.compileAndCheck("first:hilt", clean = true)
  }

  @Test
  fun cacheRestorationInAnotherDirectoryPreservesSourceOwnershipForTheNextEdit() {
    val originalProject = ContributionsProject().gradleProject
    originalProject.compileKotlin(":compileKotlin", false, "-PirHints=$irHints", "--build-cache")
    val project = ContributionsProject().gradleProject
    val restored =
      project.compileKotlin(":compileKotlin", false, "-PirHints=$irHints", "--build-cache")
    assertThat(restored.task(":lib:compileKotlin")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("first:")
    project.libSource("First.kt").delete()
    project.compileAndCheck(":")
    project.writeContribution("Restored.kt", "First", "restored")
    project.compileAndCheck("restored:")
    project.compileAndCheck("restored:", clean = true)
  }

  @Test
  fun multipleHintsInOneSourceKeepTheirOwnScopesAcrossEdits() {
    val project = ContributionsProject().gradleProject
    val source = project.libSource("First.kt")
    val original = source.readText()
    source.writeText(
      original.replace(
        "@ContributesIntoSet(AppScope::class)",
        "@ContributesIntoSet(AppScope::class)\n@ContributesIntoSet(OtherScope::class)",
      ) +
        "\n@ContributesIntoSet(AppScope::class)\n@Inject class Second : Value { override val text = \"second\" }\n",
    )
    project.compileAndCheck("first,second:first")
    source.writeText(source.readText().replace("@ContributesIntoSet(OtherScope::class)", ""))
    project.compileAndCheck("first,second:")
    source.writeText(original)
    project.compileAndCheck("first:")
    project.compileAndCheck("first:", clean = true)
  }

  @Test
  fun changingAContributedConstructorRegeneratesDownstreamCallsites() {
    val project = ContributionsProject(requiredBinding = true).gradleProject
    project.compileAndCheck("first")
    project
      .libSource("Dependency.kt")
      .writeText(
        "package test\nimport dev.zacsweers.metro.Inject\n@Inject class Dependency { val text = \"dependency\" }\n",
      )
    val source = project.libSource("First.kt")
    val original = source.readText()
    source.writeText(
      original
        .replace("class First :", "class First(val dependency: Dependency) :")
        .replace("= \"first\"", "= dependency.text"),
    )
    project.compileAndCheck("dependency")
    source.writeText(original)
    project.compileAndCheck("first")
    project.compileAndCheck("first", clean = true)
  }

  @Test
  fun contributedNestedFactoryRemainsAvailableAcrossIncrementalEdits() {
    val project = ContributionsProject().gradleProject
    val child = project.libSource("ChildGraph.kt")
    child.writeText(
      """
      package test
      import dev.zacsweers.metro.*
      @GraphExtension interface ChildGraph {
        val message: String
        @GraphExtension.Factory
        @ContributesTo(AppScope::class)
        interface Factory { fun createChild(@Provides message: String): ChildGraph }
      }
      """
        .trimIndent(),
    )
    val main = project.rootDir.resolve("src/main/kotlin/test/Main.kt")
    main.writeText(
      main.readText().replace("fun main(): String", "fun base(): String") +
        "\nfun main(): String = base() + createGraph<AppGraph>().createChild(\"child\").message\n",
    )
    project.compileAndCheck("first:child")
    project.libSource("Unrelated.kt").writeText("package test\nprivate fun unrelated() = 2\n")
    project.compileAndCheck("first:child")
    child.writeText(
      child
        .readText()
        .replace(
          "val message: String",
          "val message: String\nval size: Int get() = message.length",
        ),
    )
    project.compileAndCheck("first:child")
    project.compileAndCheck("first:child", clean = true)
  }

  @Test
  fun sameModuleGraphTracksAddedAndRemovedContributions() {
    val project = ContributionsProject().gradleProject
    val local = project.rootDir.resolve("src/main/kotlin/test/Local.kt")
    local.writeText(
      """
      package test
      import dev.zacsweers.metro.*
      @ContributesIntoSet(AppScope::class)
      @Inject class Local : Value { override val text: String = "local" }
      """
        .trimIndent(),
    )
    project.compileAndCheck("first,local:")
    val original = local.readText()
    local.writeText(original.replace("\"local\"", "\"changed\""))
    project.compileAndCheck("changed,first:")
    local.delete()
    project.compileAndCheck("first:")
    local.writeText(original)
    project.compileAndCheck("first,local:")
    project.compileAndCheck("first,local:", clean = true)
  }

  @Test
  fun switchingModesRemovesPreviousOutputsAndSupportsFurtherIncrementalEdits() {
    val project = ContributionsProject().gradleProject
    project.compileAndCheck("first:")
    project.compileKotlin(":compileKotlin", false, "-PirHints=${!irHints}")
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("first:")
    project.writeContribution("Second.kt", "Second", "second")
    val reportsBeforeEdit = project.buildReports()
    project.compileKotlin(":compileKotlin", false, "-PirHints=${!irHints}")
    project.assertIncrementalCompilation(reportsBeforeEdit)
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("first,second:")
    project.compileAndCheck("first,second:", expectIncremental = false)
    project.libSource("First.kt").delete()
    project.compileAndCheck("second:")
    project.compileAndCheck("second:", clean = true)
  }

  @Test
  fun flattenedNestedNamesKeepIndependentHintsAcrossRemovalAndRestoration() {
    val project = ContributionsProject().gradleProject
    project.writeContribution("Flat.kt", "OuterNested", "flat")
    val nested = project.libSource("Nested.kt")
    val source =
      """
      package test
      import dev.zacsweers.metro.*
      class Outer {
        @Inject @ContributesIntoSet(AppScope::class)
        class Nested : Value { override val text = "nested" }
      }
      """
        .trimIndent()
    nested.writeText(source)
    project.compileAndCheck("first,flat,nested:")
    nested.delete()
    project.compileAndCheck("first,flat:")
    nested.writeText(source)
    project.compileAndCheck("first,flat,nested:")
    project.compileAndCheck("first,flat,nested:", clean = true)
  }

  @Test
  fun longNamesWithTheSameJavaHashKeepIndependentHintsAcrossRemovalAndRestoration() {
    val project = ContributionsProject(maxGeneratedClassNameLength = 96).gradleProject
    val prefix = "A".repeat(70)
    assertThat("${prefix}Aa".hashCode()).isEqualTo("${prefix}BB".hashCode())
    val first = project.libSource("LongFirst.kt")
    val source =
      """
      package test.abcdefghijklmnopqrstuvwxyabcdefghijklmnopqrstuvwxy
      import dev.zacsweers.metro.*
      @ContributesTo(AppScope::class)
      interface ${prefix}Aa { val left: String get() = "left" }
      """
        .trimIndent()
    first.writeText(source)
    project
      .libSource("LongSecond.kt")
      .writeText(
        source.replace("${prefix}Aa", "${prefix}BB").replace("left", "right"),
      )
    project.rootDir
      .resolve("src/main/kotlin/test/Main.kt")
      .writeText(
        """
        package test
        import dev.zacsweers.metro.*
        @DependencyGraph(AppScope::class) interface AppGraph
        fun main(): String = createGraph<AppGraph>().javaClass.methods
          .filter { it.name == "getLeft" || it.name == "getRight" }
          .map { it.invoke(createGraph<AppGraph>()) as String }.sorted().joinToString()
        """
          .trimIndent(),
      )
    project.compileAndCheck("left, right", clean = true)
    first.delete()
    project.compileAndCheck("right")
    first.writeText(source)
    project.compileAndCheck("left, right")
    project.compileAndCheck("left, right", clean = true)
  }

  @Test
  fun mapKeyAndQualifierChangesUpdateExistingDownstreamMaps() {
    val project = ContributionsProject().gradleProject
    val contribution = project.libSource("Mapped.kt")
    val source =
      """
      package test
      import dev.zacsweers.metro.*
      @MapKey annotation class EntryKey(val value: String)
      @Qualifier annotation class Qualified
      @Inject @EntryKey("before") @ContributesIntoMap(AppScope::class)
      class Mapped : Value { override val text = "mapped" }
      """
        .trimIndent()
    contribution.writeText(source)
    project.rootDir
      .resolve("src/main/kotlin/test/Main.kt")
      .writeText(
        """
        package test
        import dev.zacsweers.metro.*
        @DependencyGraph(AppScope::class) interface AppGraph {
          @Multibinds(allowEmpty = true) val values: Map<String, Value>
          @Qualified @Multibinds(allowEmpty = true) val qualified: Map<String, Value>
        }
        fun main(): String {
          val graph = createGraph<AppGraph>()
          return graph.values.keys.sorted().joinToString() + ":" + graph.qualified.keys.sorted().joinToString()
        }
        """
          .trimIndent(),
      )
    project.compileAndCheck("before:")
    contribution.writeText(source.replace("before", "after"))
    project.compileAndCheck("after:")
    contribution.writeText(
      source.replace("before", "after").replace("@Inject", "@Qualified @Inject"),
    )
    project.compileAndCheck(":after")
    contribution.writeText(source)
    project.compileAndCheck("before:")
    project.compileAndCheck("before:", clean = true)
  }

  private fun GradleProject.compileAndCheck(
    expected: String,
    clean: Boolean = false,
    expectIncremental: Boolean = true,
  ): BuildResult {
    val reportsBefore = buildReports()
    val incrementalRound = expectIncremental && !clean && reportsBefore.isNotEmpty()
    val result =
      if (clean) {
        compileKotlin("clean", false, ":compileKotlin", "-PirHints=$irHints")
      } else {
        compileKotlin(":compileKotlin", false, "-PirHints=$irHints")
      }
    if (incrementalRound) {
      assertIncrementalCompilation(reportsBefore)
    }
    assertThat(invokeMain<String>(target = null)).isEqualTo(expected)
    return result
  }

  private fun GradleProject.assertIncrementalCompilation(reportsBefore: Set<File>) {
    val reports = buildReports() - reportsBefore
    assertThat(reports).isNotEmpty()
    for (report in reports) {
      assertThat(report.readText()).doesNotContain("Non-incremental compilation will be performed")
    }
    val compiledSources = reports.flatMap {
      compiledSourceNames(it, ":lib:compileKotlin") + compiledSourceNames(it, ":compileKotlin")
    }
    assertThat(compiledSources).isNotEmpty()
  }

  private fun GradleProject.libSource(name: String): File =
    rootDir.resolve("lib/src/main/kotlin/test/$name")

  private fun GradleProject.writeContribution(
    fileName: String,
    className: String,
    value: String,
    annotation: String = "@ContributesIntoSet(AppScope::class)",
  ) {
    libSource(fileName)
      .writeText(
        """
      package test
      import dev.zacsweers.metro.*
      $annotation
      @Inject class $className : Value { override val text: String = "$value" }
      """
          .trimIndent(),
      )
  }

  private fun GradleProject.buildReports(): Set<File> =
    rootDir.resolve("build/reports/kotlin-build").listFiles().orEmpty().toSet()

  private class ContributionsProject(
    private val requiredBinding: Boolean = false,
    private val hilt: Boolean = false,
    private val maxGeneratedClassNameLength: Int = 233,
  ) :
    MetroProject(
      multiplatform = false,
      reportsEnabled = false,
      additionalGradleProperties =
        listOf("org.gradle.caching=false", "kotlin.build.report.output=file"),
    ) {
    override fun StringBuilder.onBuildScript() {
      appendLine(
        """
        metro {
          compilerOptions.put("max-generated-class-name-length", "$maxGeneratedClassNameLength")
          compilerOptions.put("generate-jvm-contribution-hints-in-ir", providers.gradleProperty("irHints"))
          ${if (hilt) "interop { includeHilt() }" else ""}
        }
        """
          .trimIndent(),
      )
    }

    override fun buildGradleProject() = multiModuleProject {
      root {
        dependencies(Dependency.implementation(":lib"))
        val graph =
          if (requiredBinding) {
            """
            @DependencyGraph(AppScope::class) interface AppGraph { val value: Value }
            fun main(): String = createGraph<AppGraph>().value.text
            """
          } else {
            """
            @DependencyGraph(AppScope::class) interface AppGraph {
              @Multibinds(allowEmpty = true) val values: Set<Value>
            }
            @DependencyGraph(OtherScope::class) interface OtherGraph {
              @Multibinds(allowEmpty = true) val values: Set<Value>
            }
            fun main(): String = createGraph<AppGraph>().values.map { it.text }.sorted().joinToString(",") +
              ":" + createGraph<OtherGraph>().values.map { it.text }.sorted().joinToString(",") +
              ${if (hilt) "createGraph<HiltGraph>().hilt.text" else "\"\""}
            """
          }
        sources(
          source(graph, "Main"),
          source("private fun unrelatedConsumer() = 1", "UnrelatedConsumer"),
        )
        if (hilt) {
          sources(
            source(
              "@DependencyGraph(javax.inject.Singleton::class) interface HiltGraph",
              "HiltGraph",
            ),
          )
        }
      }
      subproject("lib") {
        if (hilt) {
          sources(
            source(
              "annotation class InstallIn(vararg val value: kotlin.reflect.KClass<*>); annotation class EntryPoint",
              "HiltAnnotations",
              packageName = "dagger.hilt",
              includeDefaultImports = false,
            ),
            source(
              "interface SingletonComponent",
              "SingletonComponent",
              packageName = "dagger.hilt.components",
            ),
            source(
              """
              @Inject class HiltValue { val text: String = "hilt" }
              @dagger.hilt.EntryPoint
              @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
              @ContributesTo(javax.inject.Singleton::class)
              interface HiltAccess { val hilt: HiltValue }
              """,
              "HiltAccess",
            ),
          )
        }
        val annotation = if (requiredBinding) "ContributesBinding" else "ContributesIntoSet"
        sources(
          source("interface Value { val text: String }; abstract class OtherScope", "Contracts"),
          source(
            """
            @$annotation(AppScope::class)
            @Inject class First : Value { override val text: String = "first" }
            """,
            "First",
          ),
          source("private fun unrelated() = 1", "Unrelated"),
        )
      }
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "irHints={0}")
    fun parameters(): List<Boolean> = listOf(false, true)
  }
}
