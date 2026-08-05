// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.gradle.Dependency.Companion.implementation
import com.autonomousapps.kit.gradle.Plugin
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.GradlePlugins
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.KotlinToolingVersion
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestCircuitVersion
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import dev.zacsweers.metro.gradle.getTestCompilerVersion
import dev.zacsweers.metro.gradle.invokeMain
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test

class CircuitMirrorRemovalIcTests : BaseIncrementalCompilationTest(KmpTarget.JVM) {
  @Test
  fun classAndFunctionScreenArgumentChangesAreDetected() {
    val selectedTarget = System.getProperty("metro.functionalTestKmpTarget")
    assumeTrue(selectedTarget == null || selectedTarget == "jvm")
    assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.0"))

    val circuitVersion = getTestCircuitVersion()
    val circuitRuntime =
      implementation("com.slack.circuit:circuit-runtime-presenter:$circuitVersion")
    val circuitAnnotations =
      implementation("com.slack.circuit:circuit-codegen-annotations:$circuitVersion")
    val composePlugin = Plugin("org.jetbrains.kotlin.plugin.compose", getTestCompilerVersion())
    val metroConfig =
      """
      @OptIn(
        dev.zacsweers.metro.gradle.DelicateMetroGradleApi::class,
        dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class,
      )
      metro {
        enableCircuitCodegen.set(true)
        // Circuit factories generated only in IR cannot publish FIR contribution hints.
        generateClassesInIr.set(false)
        compilerOptions.enable("omit-redundant-mirrors")
      }
      """
        .trimIndent()

    val fixture =
      object : MetroProject(multiplatform = false) {
        val presenters =
          source(
            presentersSource(
              classScreen = "PreviousClassScreen",
              functionScreen = "PreviousFunctionScreen",
            ),
            fileNameWithoutExtension = "CircuitPresenters",
          )

        private val graphAndMain =
          source(
            """
            import com.slack.circuit.runtime.CircuitContext
            import com.slack.circuit.runtime.Navigator
            import com.slack.circuit.runtime.presenter.Presenter
            import com.slack.circuit.runtime.screen.Screen

            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val presenterFactories: Set<Presenter.Factory>
            }

            private fun Set<Presenter.Factory>.matches(screen: Screen): Boolean =
              any { it.create(screen, Navigator.NoOp, CircuitContext.EMPTY) != null }

            fun main(): String {
              val factories = createGraph<AppGraph>().presenterFactories
              return listOf(
                factories.matches(PreviousClassScreen),
                factories.matches(UpdatedClassScreen),
                factories.matches(PreviousFunctionScreen),
                factories.matches(UpdatedFunctionScreen),
              ).joinToString()
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(graphAndMain)
            plugins(GradlePlugins.Kotlin.jvm(), composePlugin, GradlePlugins.metro)
            dependencies(implementation(":feature"), circuitRuntime, circuitAnnotations)
            buildScript { withKotlin(metroConfig) }
          }
          subproject("feature") {
            sources(presenters)
            plugins(GradlePlugins.Kotlin.jvm(), composePlugin, GradlePlugins.metro)
            dependencies(circuitRuntime, circuitAnnotations)
            buildScript { withKotlin(metroConfig) }
          }
        }
      }

    val project = fixture.gradleProject
    val firstBuild = build(project.rootDir, ":compileKotlin")
    assertThat(firstBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("true, false, true, false")

    val featureProject = project.subprojects.single { it.name.removePrefix(":") == "feature" }
    featureProject.modify(
      project.rootDir,
      fixture.presenters,
      presentersSource(
        classScreen = "UpdatedClassScreen",
        functionScreen = "UpdatedFunctionScreen",
      ),
      sourceSet = "main",
    )

    val secondBuild = build(project.rootDir, ":compileKotlin")
    assertThat(secondBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("false, true, false, true")
  }

  private fun presentersSource(classScreen: String, functionScreen: String): String =
    """
    import androidx.compose.runtime.Composable
    import com.slack.circuit.codegen.annotations.CircuitInject
    import com.slack.circuit.runtime.CircuitUiState
    import com.slack.circuit.runtime.presenter.Presenter
    import com.slack.circuit.runtime.screen.Screen

    data object PreviousClassScreen : Screen
    data object UpdatedClassScreen : Screen
    data object PreviousFunctionScreen : Screen
    data object UpdatedFunctionScreen : Screen
    data class TestState(val source: String) : CircuitUiState

    @Inject
    @CircuitInject($classScreen::class, AppScope::class)
    class ClassPresenter : Presenter<TestState> {
      @Composable override fun present(): TestState = TestState("class")
    }

    @CircuitInject($functionScreen::class, AppScope::class)
    @Composable
    fun FunctionPresenter(): TestState = TestState("function")
    """
      .trimIndent()
}
