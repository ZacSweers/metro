// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.classLoader
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import dev.zacsweers.metro.gradle.invokeMain
import dev.zacsweers.metro.gradle.supportsTopLevelFirGen
import java.io.File
import java.net.URLClassLoader
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class ContributionHintICTests :
  BaseIncrementalCompilationTest(
    target = KmpTarget.JVM,
    requiresMultiplatformIc = false,
  ) {

  // https://github.com/ZacSweers/metro/issues/2890
  @Test
  fun unrelatedPrivateEditDoesNotRecompileInjectOnlyFiles() {
    assumeFirHintsSupported()
    val fixture =
      object :
        MetroProject(
          multiplatform = false,
          additionalGradleProperties = listOf("kotlin.build.report.output=file"),
        ) {
        override fun sources() =
          listOf(
            source("@Inject class ClassInjected", "ClassInjected"),
            source("class ConstructorInjected @Inject constructor()", "ConstructorInjected"),
            source(
              """
              class MemberInjected {
                @Inject lateinit var value: String
              }
              """,
              "MemberInjected",
            ),
            source(
              """
              interface Value {
                fun value(): String
              }

              @Inject
              @ContributesBinding(AppScope::class)
              class ContributedValue : Value {
                override fun value(): String = "contributed"
              }
              """,
              "ContributedValue",
            ),
            source(
              """
              @DependencyGraph(AppScope::class)
              interface AppGraph {
                val value: Value
              }

              fun main(): String = createGraph<AppGraph>().value.value()
              """,
              "Main",
            ),
            source("private fun unrelated(): String = \"before\"", "Unrelated"),
          )
      }

    val project = fixture.gradleProject
    val firstBuild = project.compileKotlin(":compileKotlin", false, "--no-build-cache")
    assertThat(firstBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("contributed")
    val reportsDir = project.rootDir.resolve("build/reports/kotlin-build")
    val initialReports = reportsDir.listFiles().orEmpty().toSet()
    assertThat(initialReports).isNotEmpty()

    project.rootDir
      .resolve("src/main/kotlin/test/Unrelated.kt")
      .writeText("package test\nprivate fun unrelated(): String = \"after\"\n")

    val secondBuild = project.compileKotlin(":compileKotlin", false, "--no-build-cache")
    assertThat(secondBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val report = reportsDir.listFiles().orEmpty().single { it !in initialReports }
    val compiledSources = compiledMainSources(report)
    assertThat(compiledSources).contains("Unrelated.kt")
    assertThat(compiledSources)
      .containsNoneOf("ClassInjected.kt", "ConstructorInjected.kt", "MemberInjected.kt")
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("contributed")
  }

  // https://github.com/ZacSweers/metro/issues/2890
  @Test
  fun incrementalMainCompilationPreservesContributionHintsForTestGraphs() {
    assumeFirHintsSupported()
    val fixture =
      object : MetroProject(multiplatform = false) {
        override fun sources() =
          listOf(
            source(
              """
              interface Value {
                fun value(): String
              }

              @Inject
              @ContributesBinding(AppScope::class)
              class ContributedValue : Value {
                override fun value(): String = "contributed"
              }
              """,
              "ContributedValue",
            ),
            source(
              """
              @GraphExtension
              interface ChildGraph {
                val message: String

                @GraphExtension.Factory
                @ContributesTo(AppScope::class)
                interface Factory {
                  fun createChild(@Provides message: String): ChildGraph
                }
              }
              """,
              "ChildGraph",
            ),
            source("private fun unrelated(): String = \"before\"", "Unrelated"),
            dev.zacsweers.metro.gradle.source(
              """
              @DependencyGraph(AppScope::class)
              interface TestGraph {
                val value: Value
              }

              fun main(): String {
                val graph = createGraph<TestGraph>()
                return graph.value.value() + ":" + graph.createChild("child").message
              }
              """,
              "TestGraph",
              sourceSet = "test",
            ),
          )
      }

    val project = fixture.gradleProject
    val firstBuild = project.compileKotlin(":compileKotlin", false, "--no-build-cache")
    assertThat(firstBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(firstBuild.task(":compileTestKotlin")).isNull()

    project.rootDir
      .resolve("src/main/kotlin/test/Unrelated.kt")
      .writeText("package test\nprivate fun unrelated(): String = \"after\"\n")

    val secondBuild = project.compileKotlin(":compileKotlin", false, "--no-build-cache")
    assertThat(secondBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(secondBuild.task(":compileTestKotlin")).isNull()

    val testBuild = project.compileKotlin(":compileTestKotlin", false, "--no-build-cache")
    assertThat(testBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(testBuild.task(":compileTestKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val testClasses = project.rootDir.resolve("build/classes/kotlin/test")
    URLClassLoader(arrayOf(testClasses.toURI().toURL()), project.classLoader(target = null)).use {
      val value = it.loadClass("test.TestGraphKt").getMethod("main").invoke(null)
      assertThat(value).isEqualTo("contributed:child")
    }
  }

  // https://github.com/ZacSweers/metro/issues/2890
  @Test
  fun explicitlyEnabledTopLevelInjectionSurvivesIncrementalEdits() {
    assumeFirHintsSupported()
    val fixture =
      object : MetroProject(multiplatform = false) {
        override fun StringBuilder.onBuildScript() {
          appendLine(
            """
            @OptIn(dev.zacsweers.metro.gradle.DelicateMetroGradleApi::class)
            metro {
              enableTopLevelFunctionInjection.set(true)
            }
            """
              .trimIndent()
          )
        }

        override fun sources() =
          listOf(
            source("@Inject fun message(): String = \"before\"", "Message"),
            source(
              """
              @DependencyGraph
              interface AppGraph {
                val message: Message
              }

              fun main(): String = createGraph<AppGraph>().message()
              """,
              "Main",
            ),
          )
      }

    val project = fixture.gradleProject
    val firstBuild = project.compileKotlin(":compileKotlin", false, "--no-build-cache")
    assertThat(firstBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("before")

    project.rootDir
      .resolve("src/main/kotlin/test/Message.kt")
      .writeText(
        "package test\nimport dev.zacsweers.metro.Inject\n@Inject fun message(): String = \"after\"\n"
      )

    val secondBuild = project.compileKotlin(":compileKotlin", false, "--no-build-cache")
    assertThat(secondBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("after")
  }

  private fun assumeFirHintsSupported() {
    val selectedTarget = System.getProperty("metro.functionalTestKmpTarget")
    assumeTrue(selectedTarget == null || selectedTarget == "jvm")
    assumeTrue(getTestCompilerToolingVersion().supportsTopLevelFirGen())
  }

  private fun compiledMainSources(report: File): Set<String> {
    val sources = mutableSetOf<String>()
    var inMainCompilation = false
    var inIteration = false
    for (line in report.readLines()) {
      val text = line.trim()
      if (text.startsWith("Compilation log for task ")) {
        inMainCompilation = text == "Compilation log for task ':compileKotlin':"
        inIteration = false
        continue
      }
      if (!inMainCompilation) {
        continue
      }
      if (text == "Compile iteration:") {
        inIteration = true
        continue
      }
      if (inIteration) {
        val sourcePath = text.substringBefore(" <- ")
        if (sourcePath.endsWith(".kt")) {
          sources += File(sourcePath).name
        } else {
          inIteration = false
        }
      }
    }
    return sources
  }

  @Test
  fun contributionScopeArgumentChangeRemovesOldIrHint() {
    val selectedTarget = System.getProperty("metro.functionalTestKmpTarget")
    assumeTrue(selectedTarget == null || selectedTarget == "jvm")
    assumeFalse(
      "IR contribution hints are only supported before FIR contribution hint generation became required",
      getTestCompilerToolingVersion().supportsTopLevelFirGen(),
    )

    val fixture =
      object : MetroProject(multiplatform = false) {
        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val target: Target
            }

            @Inject
            class Target(val string: String)
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            class AnotherScope
            class RetainedScope

            @BindingContainer
            @ContributesTo(AppScope::class)
            @ContributesTo(RetainedScope::class)
            class StringModule {
              @Provides fun provideString(): String = "test"
            }
            """
              .trimIndent()
          )

        val changedContribution =
          """
          class AnotherScope
          class RetainedScope

          @BindingContainer
          @ContributesTo(AnotherScope::class)
          @ContributesTo(RetainedScope::class)
          class StringModule {
            @Provides fun provideString(): String = "test"
          }
          """
            .trimIndent()

        val retainedGraph =
          source(
            """
            @DependencyGraph(RetainedScope::class)
            interface RetainedGraph {
              val string: String
            }
            """
              .trimIndent()
          )

        override fun StringBuilder.onBuildScript() {
          appendLine(
            """
            @OptIn(
              dev.zacsweers.metro.gradle.DelicateMetroGradleApi::class,
              dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class,
            )
            metro {
              generateContributionHintsInFir.set(false)
            }
            """
              .trimIndent()
          )
        }

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph)
            dependencies(Dependency.implementation(":lib"))
          }
          subproject("lib") { sources(bindingContainer) }
          subproject("retained") {
            sources(retainedGraph)
            dependencies(Dependency.implementation(":lib"))
          }
        }
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    val firstBuild = project.compileKotlin(task = ":compileKotlin")
    assertThat(firstBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val firstRetainedBuild = project.compileKotlin(task = ":retained:compileKotlin")
    assertThat(firstRetainedBuild.task(":retained:compileKotlin")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)

    libProject.modify(
      rootDir = project.rootDir,
      source = fixture.bindingContainer,
      content = fixture.changedContribution,
      sourceSet = "main",
    )

    val secondBuild = project.compileKotlinAndFail(task = ":compileKotlin")
    assertThat(secondBuild.output).contains("[Metro/MissingBinding]")
    val secondRetainedBuild = project.compileKotlin(task = ":retained:compileKotlin")
    assertThat(secondRetainedBuild.task(":retained:compileKotlin")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
  }
}
