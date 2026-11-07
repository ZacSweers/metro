// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The JVM experiment must preserve FIR hints for shared sources and every KLIB target. */
@RunWith(Parameterized::class)
class JvmContributionHintPlatformTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {
  @Test
  fun sharedContributionsRemainVisibleWithJvmExperimentEnabled() {
    val fixture =
      object : MetroProject(additionalGradleProperties = listOf("org.gradle.caching=false")) {
        override fun StringBuilder.onBuildScript() {
          appendLine(
            "metro { compilerOptions.put(\"generate-jvm-contribution-hints-in-ir\", \"true\") }",
          )
        }

        override fun buildGradleProject() = multiModuleProject {
          root {
            dependencies(Dependency.implementation(":lib"))
            sources(
              source(
                """
                @DependencyGraph(AppScope::class) interface AppGraph
                fun message(): String = createGraph<AppGraph>().message
                """,
                "Graph",
              ),
            )
          }
          subproject("lib") {
            sources(
              source(
                """
                @ContributesTo(AppScope::class)
                interface Contribution {
                  val message: String
                  @Provides fun provideMessage(): String = "before"
                }
                """,
                "Contribution",
              ),
            )
          }
        }
      }
    val project = fixture.gradleProject
    project.compileKotlin()
    val source = project.rootDir.resolve("lib/src/commonMain/kotlin/test/Contribution.kt")
    source.writeText(source.readText().replace("\"before\"", "\"after\""))
    val changed = project.compileKotlin()
    assertThat(changed.task(compileTaskFor("lib"))?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val warm = project.compileKotlin()
    assertThat(warm.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun parameters(): List<KmpTarget> = KmpTarget.selectedTargets()
  }
}
