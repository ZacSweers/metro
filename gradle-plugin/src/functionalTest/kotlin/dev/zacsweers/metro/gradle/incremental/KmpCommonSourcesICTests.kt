// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.GradlePlugins
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.KotlinToolingVersion
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import dev.zacsweers.metro.gradle.invokeMain
import dev.zacsweers.metro.gradle.source as testSource
import java.io.File
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Metro's contribution hints keep every contributing file dirty on each incremental build. When a
 * contributing file is in `commonMain`, Kotlin falls back to a full rebuild (KT-62686) unless
 * incremental compilation of common sources is enabled.
 *
 * A full rebuild doesn't list its sources in the build report. So each exact compiled-source
 * assertion here also checks that the build stayed incremental.
 */
class KmpCommonSourcesICTests : BaseIncrementalCompilationTest(KmpTarget.JVM) {

  // The public flag is new in Kotlin 2.5.0-Beta1. On 2.4.20, its internal equivalents fail to write
  // JVM metadata for Metro's FIR-generated hints.
  @Before
  fun assumeCommonSourcesIcSupported() {
    val selectedTarget = System.getProperty("metro.functionalTestKmpTarget")
    assumeTrue(selectedTarget == null || selectedTarget == "jvm")
    assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.5.0-Beta1"))
  }

  @Test
  fun commonContributorsWithPlatformGraph() {
    val fixture =
      object : CommonSourcesIcProject() {
        override fun sources() =
          listOf(
            valueSource,
            source(
              """
              @ContributesBinding(AppScope::class)
              @Inject
              class CommonValue : Value {
                override fun value(): String = "common"
              }
              """,
              "CommonValue",
            ),
            testSource(
              """
              @DependencyGraph(AppScope::class)
              interface AppGraph {
                val value: Value
              }

              fun main(): String = createGraph<AppGraph>().value.value()
              """,
              "Main",
              sourceSet = "jvmMain",
            ),
            testSource(UNRELATED_BEFORE, "Unrelated", sourceSet = "jvmMain"),
          )
      }

    val project = fixture.gradleProject
    project.compileWithReport()
    assertThat(project.invokeMain<String>()).isEqualTo("common")

    project.writeSource("jvmMain", "Unrelated", UNRELATED_AFTER)
    // The contributor is still recompiled for its hint.
    assertThat(project.compileWithReport().compiledSources())
      .containsExactly("Unrelated.kt", "CommonValue.kt")
    assertThat(project.invokeMain<String>()).isEqualTo("common")

    project.writeSource("commonMain", "AddedValue", addedValueSource("CommonValue"))
    assertThat(project.compileWithReport().compiledSources()).contains("Main.kt")
    assertThat(project.invokeMain<String>()).isEqualTo("added")

    project.deleteSource("commonMain", "AddedValue")
    assertThat(project.compileWithReport().compiledSources()).contains("Main.kt")
    assertThat(project.invokeMain<String>()).isEqualTo("common")
  }

  @Test
  fun commonGraphAndGraphExtension() {
    val fixture =
      object : CommonSourcesIcProject() {
        override fun sources() =
          listOf(
            valueSource,
            source(
              """
              @ContributesBinding(AppScope::class)
              @Inject
              class CommonValue : Value {
                override fun value(): String = "common"
              }
              """,
              "CommonValue",
            ),
            source(
              """
              abstract class LoggedInScope private constructor()

              interface Session {
                val name: String
              }

              @ContributesBinding(LoggedInScope::class)
              @Inject
              class RealSession : Session {
                override val name: String = "session"
              }

              @GraphExtension(LoggedInScope::class)
              interface LoggedInGraph {
                val session: Session

                @GraphExtension.Factory
                @ContributesTo(AppScope::class)
                fun interface Factory {
                  fun createLoggedInGraph(): LoggedInGraph
                }
              }
              """,
              "LoggedInGraph",
            ),
            source(
              """
              @DependencyGraph(AppScope::class)
              interface AppGraph {
                val value: Value
              }
              """,
              "AppGraph",
            ),
            source(UNRELATED_BEFORE, "CommonUnrelated"),
            testSource(
              """
              fun main(): String {
                val graph = createGraph<AppGraph>()
                return graph.value.value() + "," + graph.createLoggedInGraph().session.name
              }
              """,
              "Main",
              sourceSet = "jvmMain",
            ),
            testSource(UNRELATED_BEFORE, "Unrelated", sourceSet = "jvmMain"),
          )
      }

    val project = fixture.gradleProject
    project.compileWithReport()
    assertThat(project.invokeMain<String>()).isEqualTo("common,session")

    project.writeSource("jvmMain", "Unrelated", UNRELATED_AFTER)
    assertThat(project.compileWithReport().compiledSources())
      .containsExactly("Unrelated.kt", "CommonValue.kt", "LoggedInGraph.kt")
    assertThat(project.invokeMain<String>()).isEqualTo("common,session")

    // Without common-sources IC, any commonMain edit is a full rebuild.
    project.writeSource("commonMain", "CommonUnrelated", UNRELATED_AFTER)
    assertThat(project.compileWithReport().compiledSources())
      .containsExactly("CommonUnrelated.kt", "CommonValue.kt", "LoggedInGraph.kt")
    assertThat(project.invokeMain<String>()).isEqualTo("common,session")

    project.writeSource("commonMain", "AddedValue", addedValueSource("CommonValue"))
    assertThat(project.compileWithReport().compiledSources()).contains("AppGraph.kt")
    assertThat(project.invokeMain<String>()).isEqualTo("added,session")

    project.writeSource(
      "commonMain",
      "AddedSession",
      """
      package test

      import dev.zacsweers.metro.*

      @ContributesBinding(LoggedInScope::class, replaces = [RealSession::class])
      @Inject
      class AddedSession : Session {
        override val name: String = "added"
      }
      """,
    )
    project.compileWithReport()
    assertThat(project.invokeMain<String>()).isEqualTo("added,added")

    project.deleteSource("commonMain", "AddedValue")
    project.deleteSource("commonMain", "AddedSession")
    assertThat(project.compileWithReport().compiledSources()).contains("AppGraph.kt")
    assertThat(project.invokeMain<String>()).isEqualTo("common,session")
  }

  @Test
  fun commonContributorsInUpstreamModule() {
    val fixture =
      object : CommonSourcesIcProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(
              source(
                """
                @DependencyGraph(AppScope::class)
                interface AppGraph {
                  val value: Value
                }
                """,
                "AppGraph",
              ),
              testSource(
                "fun main(): String = createGraph<AppGraph>().value.value()",
                "Main",
                sourceSet = "jvmMain",
              ),
              testSource(UNRELATED_BEFORE, "Unrelated", sourceSet = "jvmMain"),
            )
            dependencies(com.autonomousapps.kit.gradle.Dependency.implementation(":lib"))
          }
          subproject("lib") {
            sources(
              valueSource,
              source(
                """
                @ContributesBinding(AppScope::class)
                @Inject
                class LibValue : Value {
                  override fun value(): String = "lib"
                }
                """,
                "LibValue",
              ),
            )
          }
        }
      }

    val project = fixture.gradleProject
    val libTask = compileTaskFor("lib")
    val appTask = compileTaskFor()
    project.compileWithReport()
    assertThat(project.invokeMain<String>()).isEqualTo("lib")

    project.writeSource("jvmMain", "Unrelated", UNRELATED_AFTER)
    assertThat(project.compileWithReport().compiledSources(appTask)).containsExactly("Unrelated.kt")
    assertThat(project.invokeMain<String>()).isEqualTo("lib")

    project.writeSource(
      "commonMain",
      "AddedValue",
      addedValueSource("LibValue"),
      projectPath = "lib",
    )
    val addReport = project.compileWithReport()
    assertThat(addReport.compiledSources(libTask)).containsExactly("AddedValue.kt", "LibValue.kt")
    assertThat(addReport.compiledSources(appTask)).contains("AppGraph.kt")
    assertThat(project.invokeMain<String>()).isEqualTo("added")

    project.deleteSource("commonMain", "AddedValue", projectPath = "lib")
    val removeReport = project.compileWithReport()
    assertThat(removeReport.compiledSources(appTask)).contains("AppGraph.kt")
    assertThat(project.invokeMain<String>()).isEqualTo("lib")
  }

  @Test
  fun androidTarget() {
    val fixture =
      object : CommonSourcesIcProject() {
        override fun buildGradleProject(): GradleProject {
          val androidHome = System.getProperty("metro.androidHome")
          assumeTrue(androidHome != null)
          val sdkDir = File(androidHome).invariantSeparatorsPath
          return newGradleProjectBuilder(DslKind.KOTLIN)
            .withRootProject {
              withMetroSettings()
              withFile("local.properties", "sdk.dir=$sdkDir")
              sources =
                mutableListOf(
                  valueSource,
                  source(
                    """
                    @ContributesBinding(AppScope::class)
                    @Inject
                    class CommonValue : Value {
                      override fun value(): String = "common"
                    }
                    """,
                    "CommonValue",
                  ),
                  testSource(
                    """
                    @DependencyGraph(AppScope::class)
                    interface AppGraph {
                      val value: Value
                    }

                    fun main(): String = createGraph<AppGraph>().value.value()
                    """,
                    "Main",
                    sourceSet = "androidMain",
                  ),
                  testSource(UNRELATED_BEFORE, "Unrelated", sourceSet = "androidMain"),
                )
              withBuildScript {
                plugins(
                  GradlePlugins.Kotlin.multiplatform(),
                  GradlePlugins.agpKmp,
                  GradlePlugins.metro,
                )
                withKotlin(
                  """
                  kotlin {
                    android {
                      namespace = "test.app"
                      minSdk = 36
                      compileSdk = ${System.getProperty("metro.androidCompileSdk")}
                    }
                  }

                  ${buildMetroBlock()}
                  """
                    .trimIndent(),
                )
              }
            }
            .write()
        }
      }

    val project = fixture.gradleProject
    val androidTask = ":compileAndroidMain"
    project.compileWithReport(androidTask)
    assertThat(project.invokeMain<String>(target = "android")).isEqualTo("common")

    project.writeSource("androidMain", "Unrelated", UNRELATED_AFTER)
    assertThat(project.compileWithReport(androidTask).compiledSources(androidTask))
      .containsExactly("Unrelated.kt", "CommonValue.kt")
    assertThat(project.invokeMain<String>(target = "android")).isEqualTo("common")

    project.writeSource("commonMain", "AddedValue", addedValueSource("CommonValue"))
    assertThat(project.compileWithReport(androidTask).compiledSources(androidTask))
      .contains("Main.kt")
    assertThat(project.invokeMain<String>(target = "android")).isEqualTo("added")

    project.deleteSource("commonMain", "AddedValue")
    assertThat(project.compileWithReport(androidTask).compiledSources(androidTask))
      .contains("Main.kt")
    assertThat(project.invokeMain<String>(target = "android")).isEqualTo("common")
  }

  private abstract class CommonSourcesIcProject :
    MetroProject(
      additionalGradleProperties =
        listOf(
          "kotlin.build.report.output=file",
          "kotlin.jvm.enableIncrementalCompilationOfCommonSources=true",
        ),
    ) {
    val valueSource =
      source(
        """
        interface Value {
          fun value(): String
        }
        """,
        "Value",
      )

    override fun multiplatformTargetsBlock(): String = "kotlin { jvm() }\n"
  }

  private inner class BuildReport(private val file: File) {
    fun compiledSources(taskPath: String = compileTaskFor()): Set<String> =
      compiledSourceNames(file, taskPath)
  }

  private fun GradleProject.compileWithReport(task: String = compileTaskFor()): BuildReport {
    val before = reportFiles()
    val result = compileKotlin(task)
    assertThat(result.task(task)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    return BuildReport((reportFiles() - before).single())
  }

  private fun GradleProject.reportFiles(): Set<File> =
    rootDir.resolve("build/reports/kotlin-build").listFiles().orEmpty().toSet()

  private fun GradleProject.sourceFile(sourceSet: String, name: String, projectPath: String): File {
    val projectDir = if (projectPath.isEmpty()) rootDir else rootDir.resolve(projectPath)
    return projectDir.resolve("src/$sourceSet/kotlin/test/$name.kt")
  }

  private fun GradleProject.writeSource(
    sourceSet: String,
    name: String,
    content: String,
    projectPath: String = "",
  ) {
    sourceFile(sourceSet, name, projectPath).writeText(content.trimIndent())
  }

  private fun GradleProject.deleteSource(
    sourceSet: String,
    name: String,
    projectPath: String = "",
  ) {
    check(sourceFile(sourceSet, name, projectPath).delete())
  }

  private companion object {
    const val UNRELATED_BEFORE = "private fun unrelated(): String = \"before\""
    const val UNRELATED_AFTER = "package test\nprivate fun unrelated(): String = \"after\"\n"

    fun addedValueSource(replaced: String) =
      """
      package test

      import dev.zacsweers.metro.*

      @ContributesBinding(AppScope::class, replaces = [$replaced::class])
      @Inject
      class AddedValue : Value {
        override fun value(): String = "added"
      }
      """
  }
}
