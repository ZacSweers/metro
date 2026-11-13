// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroOptionOverrides
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.cleanOutputLine
import dev.zacsweers.metro.gradle.invokeMain
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verifies that graphs pick up changes to the generic providers their derived bindings use. */
@RunWith(Parameterized::class)
class DerivedBindingICTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
  }

  @Test
  fun changingGenericProviderBodyIsPickedUp() {
    val fixture =
      object : MetroProject(metroOptions = MetroOptionOverrides(enableDerivedBindings = true)) {
        override fun sources() = listOf(encoderTypes, encoders, appGraph, main)

        val encoders =
          source(
            """
            @BindingContainer
            object Encoders {
              @Provides fun string(): Encoder<String> = NamedEncoder("string")

              @Provides
              fun <T> list(element: Encoder<T>): Encoder<List<T>> =
                NamedEncoder("list(${'$'}{element.name})")
            }
            """
              .trimIndent(),
          )
      }

    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("list(string)") }

    project.modify(
      fixture.encoders,
      """
      @BindingContainer
      object Encoders {
        @Provides fun string(): Encoder<String> = NamedEncoder("string")

        @Provides
        fun <T> list(element: Encoder<T>): Encoder<List<T>> =
          NamedEncoder("changed(${'$'}{element.name})")
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("changed(string)") }
  }

  @Test
  fun addingMoreSpecificGenericProviderIsPickedUp() {
    val fixture =
      object : MetroProject(metroOptions = MetroOptionOverrides(enableDerivedBindings = true)) {
        override fun sources() = listOf(encoderTypes, encoders, appGraph, main)

        val encoders =
          source(
            """
            @BindingContainer
            object Encoders {
              @Provides fun string(): Encoder<String> = NamedEncoder("string")

              @Provides fun <T> anything(): Encoder<T> = NamedEncoder("anything")
            }
            """
              .trimIndent(),
          )
      }

    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("anything") }

    // `list` matches `Encoder<List<String>>` more specifically than `anything` does.
    project.modify(
      fixture.encoders,
      """
      @BindingContainer
      object Encoders {
        @Provides fun string(): Encoder<String> = NamedEncoder("string")

        @Provides fun <T> anything(): Encoder<T> = NamedEncoder("anything")

        @Provides
        fun <T> list(element: Encoder<T>): Encoder<List<T>> =
          NamedEncoder("list(${'$'}{element.name})")
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("list(string)") }
  }

  @Test
  fun removingGenericProviderIsPickedUp() {
    val fixture =
      object : MetroProject(metroOptions = MetroOptionOverrides(enableDerivedBindings = true)) {
        override fun sources() = listOf(encoderTypes, encoders, appGraph, main)

        val encoders =
          source(
            """
            @BindingContainer
            object Encoders {
              @Provides fun string(): Encoder<String> = NamedEncoder("string")

              @Provides fun <T> anything(): Encoder<T> = NamedEncoder("anything")

              @Provides
              fun <T> list(element: Encoder<T>): Encoder<List<T>> =
                NamedEncoder("list(${'$'}{element.name})")
            }
            """
              .trimIndent(),
          )
      }

    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("list(string)") }

    // Without `list`, `anything` is the only provider left that matches `Encoder<List<String>>`.
    project.modify(
      fixture.encoders,
      """
      @BindingContainer
      object Encoders {
        @Provides fun string(): Encoder<String> = NamedEncoder("string")

        @Provides fun <T> anything(): Encoder<T> = NamedEncoder("anything")
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("anything") }
  }

  @Test
  fun changingGenericProviderBoundIsPickedUp() {
    val fixture =
      object : MetroProject(metroOptions = MetroOptionOverrides(enableDerivedBindings = true)) {
        override fun sources() = listOf(encoderTypes, encoders, appGraph, main)

        val encoders =
          source(
            """
            @BindingContainer
            object Encoders {
              @Provides fun string(): Encoder<String> = NamedEncoder("string")

              @Provides
              fun <T : Any> list(element: Encoder<T>): Encoder<List<T>> =
                NamedEncoder("list(${'$'}{element.name})")
            }
            """
              .trimIndent(),
          )
      }

    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("list(string)") }

    // `String` isn't a `Number`, so `list` no longer matches `Encoder<List<String>>`.
    project.modify(
      fixture.encoders,
      """
      @BindingContainer
      object Encoders {
        @Provides fun string(): Encoder<String> = NamedEncoder("string")

        @Provides
        fun <T : Number> list(element: Encoder<T>): Encoder<List<T>> =
          NamedEncoder("list(${'$'}{element.name})")
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlinAndFail()
    val output = secondBuildResult.output.cleanOutputLine()
    assertThat(output).contains("[Metro/MissingBinding] No binding found for")
    // The error names the bound that rejected the provider.
    assertThat(output).contains("`T : Number`")
  }

  @Test
  fun changingGenericProviderParametersInLibraryIsPickedUp() {
    val fixture =
      object : MetroProject(metroOptions = MetroOptionOverrides(enableDerivedBindings = true)) {
        val encodersContent =
          """
          @BindingContainer
          object Encoders {
            @Provides
            fun <T> list(element: Encoder<T>): Encoder<List<T>> =
              NamedEncoder("list(${'$'}{element.name})")
          }
          """
            .trimIndent()

        val encoders = source(encodersContent)

        private val stringEncoder =
          source(
            """
            @BindingContainer
            object StringEncoders {
              @Provides fun string(): Encoder<String> = NamedEncoder("string")
            }
            """
              .trimIndent(),
          )

        private val crossModuleGraph =
          source(
            """
            @DependencyGraph(bindingContainers = [Encoders::class, StringEncoders::class])
            interface AppGraph {
              val encoder: Encoder<List<String>>
            }
            """
              .trimIndent(),
          )

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(stringEncoder, crossModuleGraph, main)
            dependencies(Dependency.implementation(":lib"))
          }
          subproject("lib") { sources(encoderTypes, encoders) }
        }
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("list(string)") }

    // The derived binding now needs a `Separator`, which nothing provides.
    libProject.modify(
      project.rootDir,
      fixture.encoders,
      """
      class Separator

      @BindingContainer
      object Encoders {
        @Provides
        fun <T> list(element: Encoder<T>, separator: Separator): Encoder<List<T>> =
          NamedEncoder("list(${'$'}{element.name})")
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output.cleanOutputLine())
      .contains("[Metro/MissingBinding] No binding found for Separator")
  }

  private val encoderTypes =
    dev.zacsweers.metro.gradle.source(
      """
      interface Encoder<T> {
        val name: String
      }

      class NamedEncoder<T>(override val name: String) : Encoder<T>
      """
        .trimIndent(),
      fileNameWithoutExtension = "EncoderTypes",
      sourceSet = "commonMain",
    )

  private val appGraph =
    dev.zacsweers.metro.gradle.source(
      """
      @DependencyGraph(bindingContainers = [Encoders::class])
      interface AppGraph {
        val encoder: Encoder<List<String>>
      }
      """
        .trimIndent(),
      sourceSet = "commonMain",
    )

  private val main =
    dev.zacsweers.metro.gradle.source(
      """
      fun main(): String {
        return createGraph<AppGraph>().encoder.name
      }
      """
        .trimIndent(),
      fileNameWithoutExtension = "Main",
      sourceSet = "commonMain",
    )
}
