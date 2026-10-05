// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Compiles static providers and generated helpers in both companion modes on the selected target.
 */
@RunWith(Parameterized::class)
class CompanionBlocksKmpTest(private val target: KmpTarget) {
  @Test
  fun staticProviderAndCreator() {
    assumeCompanionBlocksSupported()
    for (mode in listOf("companion-block", "compatibility")) {
      val project =
        object : CompanionProject(mode, multiplatform = true) {
            override fun sources() =
              listOf(
                source(
                  """
                @BindingContainer
                abstract class AppBindings private constructor() {
                  companion {
                    @Provides fun value(): String = "value"
                  }
                }

                @Inject class Message(val value: String)

                @DependencyGraph(bindingContainers = [AppBindings::class])
                interface AppGraph {
                  val message: Message
                }

                fun createValue(): String = AppGraph().message.value
                """,
                  "AppGraph",
                )
              )
          }
          .gradleProject

      val result = build(project.rootDir, target.compileTaskName)
      assertThat(result.task(":${target.compileTaskName}")?.outcome)
        .isAnyOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
  }
}
