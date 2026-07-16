// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency.Companion.implementation
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.KotlinToolingVersion
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeICTests : BaseIncrementalCompilationTest(KmpTarget.NATIVE_HOST) {

  // https://kotlinlang.slack.com/archives/C053VDMKFAR/p1784130052825989
  // https://kotlinlang.org/docs/native-improving-compilation-time.html#try-incremental-compilation-of-klib-artifacts
  @Test
  fun nativeKlibChangeRelinksIncrementally() {
    assumeTrue(
      "Kotlin/Native incremental klib compilation is enabled by default in Kotlin 2.4.20-Beta1+",
      getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.20-Beta1"),
    )

    val fixture =
      object : MetroProject(additionalGradleProperties = listOf("kotlin.incremental.native=true")) {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraphAndMain)
            dependencies(implementation(":lib"))
          }
          subproject("lib") { sources(messageBinding) }
        }

        override fun multiplatformTargetsBlock(): String = buildString {
          appendLine("kotlin {")
          appendLine("  ${KmpTarget.NATIVE_HOST.gradleTargetName} {")
          appendLine("    binaries {")
          appendLine("      executable {")
          appendLine("        entryPoint = \"test.main\"")
          appendLine("      }")
          appendLine("    }")
          appendLine("  }")
          appendLine("}")
        }

        val messageBinding =
          source(
            """
            interface Message {
              val value: String
            }

            @Inject
            class MessageImpl : Message {
              override val value: String
                get() = "before"
            }
            """
              .trimIndent()
          )

        private val appGraphAndMain =
          source(
            """
            @BindingContainer
            interface MessageBindings {
              @Binds fun bindMessage(impl: MessageImpl): Message
            }

            @DependencyGraph(
              scope = Unit::class,
              bindingContainers = [MessageBindings::class],
            )
            interface AppGraph {
              val message: Message
            }

            fun main() {
              println("native-ic:${'$'}{createGraph<AppGraph>().message.value}")
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }
    val nativeTaskSuffix =
      KmpTarget.NATIVE_HOST.gradleTargetName.replaceFirstChar { it.titlecase() }
    val runTask = ":runDebugExecutable$nativeTaskSuffix"
    val linkTask = ":linkDebugExecutable$nativeTaskSuffix"

    val firstBuildResult = project.compileKotlin(task = runTask)
    assertThat(firstBuildResult.task(linkTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(firstBuildResult.output).contains("native-ic:before")

    libProject.modify(
      project.rootDir,
      fixture.messageBinding,
      """
      interface Message {
        val value: String
      }

      @Inject
      class MessageImpl : Message {
        override val value: String
          get() = "after"
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin(task = runTask)
    assertThat(secondBuildResult.task(compileTaskFor("lib"))?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(secondBuildResult.task(linkTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(secondBuildResult.output).doesNotContain("Incremental compilation failed")
    assertThat(secondBuildResult.output).contains("native-ic:after")
    assertThat(secondBuildResult.output).doesNotContain("native-ic:before")
  }
}
