// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.invokeMain
import dev.zacsweers.metro.gradle.source as testSource
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test

class ContributionScopeICTests : BaseIncrementalCompilationTest(KmpTarget.JVM) {
  @Test
  fun commonAndPlatformContributionsRemainVisibleAcrossScopes() {
    val selectedTarget = System.getProperty("metro.functionalTestKmpTarget")
    assumeTrue(selectedTarget == null || selectedTarget == "jvm")

    val fixture =
      object : MetroProject() {
        val common =
          source(
            """
            object FirstScope
            object SecondScope

            @Inject class CommonValue {
              val value: String = "common"
            }

            @ContributesTo(FirstScope::class)
            @ContributesTo(SecondScope::class)
            interface CommonContribution {
              val common: CommonValue
            }
            """,
            "CommonContribution",
          )

        val platform =
          testSource(
            """
            @Inject class PlatformValue {
              val value: String = "platform"
            }

            @ContributesTo(SecondScope::class)
            interface PlatformContribution {
              val platform: PlatformValue
            }
            """,
            "PlatformContribution",
            sourceSet = "jvmMain",
          )

        val graphs =
          testSource(
            """
            @DependencyGraph(FirstScope::class)
            interface FirstGraph

            @DependencyGraph(SecondScope::class)
            interface SecondGraph

            fun main(): String {
              val first = createGraph<FirstGraph>()
              val second = createGraph<SecondGraph>()
              return first.common.value + "," + second.common.value + "," + second.platform.value
            }
            """,
            "Main",
            sourceSet = "jvmMain",
          )

        override fun sources() = listOf(common, platform, graphs)

        override fun multiplatformTargetsBlock(): String = "kotlin { jvm() }\n"
      }

    val project = fixture.gradleProject
    val firstBuild = project.compileKotlin()
    assertThat(firstBuild.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<String>()).isEqualTo("common,common,platform")

    val platformSource = project.rootDir.resolve("src/jvmMain/kotlin/test/PlatformContribution.kt")
    platformSource.writeText(platformSource.readText().replace("\"platform\"", "\"updated\""))

    val secondBuild = project.compileKotlin()
    assertThat(secondBuild.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<String>()).isEqualTo("common,common,updated")
  }
}
