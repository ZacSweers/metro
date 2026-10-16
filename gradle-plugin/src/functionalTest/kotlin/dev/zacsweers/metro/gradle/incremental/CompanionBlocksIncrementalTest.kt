// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.KotlinToolingVersion
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import dev.zacsweers.metro.gradle.invokeMain
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Confirms a binary static provider's changed dependencies invalidate its consumer graph. */
class CompanionBlocksIncrementalTest :
  BaseIncrementalCompilationTest(KmpTarget.JVM, requiresMultiplatformIc = false) {
  @Test
  fun providerSignatureChangeRecompilesConsumer() {
    assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.5.0-dev-7307"))
    val project =
      object :
          MetroProject(
            multiplatform = false,
            additionalGradleProperties = listOf("kotlin.build.report.output=file"),
          ) {
          override fun StringBuilder.onBuildScript() {
            appendLine(
              """
              kotlin {
                compilerOptions {
                  freeCompilerArgs.addAll("-Xcompanion-blocks", "-P", "plugin:dev.zacsweers.metro.compiler:companion-mode=companion-block")
                }
              }
              """
                .trimIndent(),
            )
          }

          override fun buildGradleProject() = multiModuleProject {
            root {
              dependencies(Dependency.implementation(":lib"))
              sources(
                source(
                  """
                @DependencyGraph(bindingContainers = [AppBindings::class])
                interface AppGraph {
                  val value: String
                  @Provides fun number(): Int = 2
                }

                fun main(): String = AppGraph().value
                """,
                  "AppGraph",
                ),
              )
            }
            subproject("lib") {
              sources(
                source(
                  """
                @BindingContainer
                interface AppBindings {
                  companion {
                    @Provides fun value(): String = "initial"
                  }
                }
                """,
                  "AppBindings",
                ),
              )
            }
          }
        }
        .gradleProject

    project.compileKotlin(task = ":compileKotlin")
    assertThat(project.invokeMain<String>(className = "test.AppGraphKt", target = null))
      .isEqualTo("initial")

    val provider = project.rootDir.resolve("lib/src/main/kotlin/test/AppBindings.kt")
    provider.writeText(
      provider
        .readText()
        .replace(
          "fun value(): String = \"initial\"",
          "fun value(number: Int): String = \"updated\" + number",
        ),
    )
    val reportDirectory = project.rootDir.resolve("build/reports/kotlin-build")
    val previousReports = reportDirectory.listFiles().orEmpty().toSet()
    val result = project.compileKotlin(task = ":compileKotlin")
    assertThat(result.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val report = reportDirectory.listFiles().orEmpty().first { it !in previousReports }
    assertThat(compiledSourceNames(report, ":compileKotlin")).contains("AppGraph.kt")
    assertThat(report.readText()).doesNotContain("IC_FAILED_TO_COMPILE_INCREMENTALLY")
    assertThat(project.invokeMain<String>(className = "test.AppGraphKt", target = null))
      .isEqualTo("updated2")
  }
}
