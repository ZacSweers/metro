// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.extensions.circuit

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
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test

class CircuitNativeTest {
  // https://github.com/ZacSweers/metro/issues/2882
  @Test
  fun subUiFunctionFactoriesLinkAndCreateOnNative() {
    assumeTrue(
      "Compose UI Native artifacts require macOS",
      KmpTarget.NATIVE_HOST.gradleTargetName.startsWith("macos"),
    )
    assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.3.20"))

    val composePlugin = Plugin("org.jetbrains.kotlin.plugin.compose", getTestCompilerVersion())
    val circuit = implementation("com.slack.circuit:circuitx-subcircuit:${getTestCircuitVersion()}")
    val fixture =
      object : MetroProject() {
        override fun multiplatformTargetsBlock(): String =
          "kotlin { ${KmpTarget.NATIVE_HOST.gradleTargetName}() }\n"

        private fun circuitConfiguration(): String = buildString {
          appendLine(multiplatformTargetsBlock())
          appendLine(buildMetroBlock())
          appendLine(
            """
            @OptIn(dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class)
            metro {
              enableCircuitCodegen.set(true)
            }
            """
              .trimIndent(),
          )
        }

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(app)
            dependencies(
              implementation(":feature"),
              circuit,
              implementation("app.cash.molecule:molecule-runtime:2.2.0"),
            )
            buildScript {
              plugins(GradlePlugins.Kotlin.multiplatform(), composePlugin, GradlePlugins.metro)
              withKotlin(
                circuitConfiguration() +
                  """
                  kotlin {
                    ${KmpTarget.NATIVE_HOST.gradleTargetName} {
                      binaries.executable {
                        entryPoint = "test.main"
                      }
                    }
                  }
                  """
                    .trimIndent(),
              )
            }
          }
          // Calls to Content in the feature module would hide the Native linkage failure.
          subproject("feature") {
            sources(uiFunctions)
            dependencies(circuit)
            buildScript {
              plugins(GradlePlugins.Kotlin.multiplatform(), composePlugin, GradlePlugins.metro)
              withKotlin(circuitConfiguration())
            }
          }
        }

        private val uiFunctions =
          source(
            $$"""
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import com.slack.circuit.subcircuit.SubCircuitInject
            import com.slack.circuit.subcircuit.SubCircuitOuterEvent
            import com.slack.circuit.subcircuit.SubCircuitUiState
            import com.slack.circuit.subcircuit.SubScreen

            sealed interface OuterEvent : SubCircuitOuterEvent
            data object StatefulScreen : SubScreen<OuterEvent>
            data object StatelessScreen : SubScreen<OuterEvent>
            data object CapturedStatefulScreen : SubScreen<OuterEvent>
            data object CapturedStatelessScreen : SubScreen<OuterEvent>
            data object UnknownScreen : SubScreen<OuterEvent>
            data class State(val label: String) : SubCircuitUiState
            object TestModifier : Modifier.Element
            val uncapturedCalls = mutableListOf<String>()

            @SingleIn(AppScope::class)
            @Inject
            class Recorder {
              val calls = mutableListOf<String>()
            }

            @SubCircuitInject(StatefulScreen::class, AppScope::class)
            @Composable
            fun StatefulUi(model: State, mod: Modifier) {
              uncapturedCalls += "stateful:${model.label}:${mod === TestModifier}"
            }

            @SubCircuitInject(StatelessScreen::class, AppScope::class)
            @Composable
            fun StatelessUi(mod: Modifier) {
              uncapturedCalls += "stateless:${mod === TestModifier}"
            }

            @SubCircuitInject(CapturedStatefulScreen::class, AppScope::class)
            @Composable
            fun CapturedStatefulUi(recorder: Recorder, mod: Modifier, model: State) {
              recorder.calls += "stateful:${model.label}:${mod === TestModifier}"
            }

            @SubCircuitInject(CapturedStatelessScreen::class, AppScope::class)
            @Composable
            fun CapturedStatelessUi(mod: Modifier, recorder: Recorder) {
              recorder.calls += "stateless:${mod === TestModifier}"
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "SubUis",
          )

        private val app =
          source(
            """
            import app.cash.molecule.RecompositionMode
            import app.cash.molecule.moleculeFlow
            import com.slack.circuit.subcircuit.SubScreen
            import com.slack.circuit.subcircuit.SubUi
            import com.slack.circuit.subcircuit.SubUiFactory
            import kotlinx.coroutines.flow.first
            import kotlinx.coroutines.runBlocking

            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val uiFactories: Set<SubUiFactory>
              val recorder: Recorder
            }

            fun main() = runBlocking {
              val graph = createGraph<AppGraph>()
              val factories = graph.uiFactories
              check(factories.size == 4)
              val stateful = factories.createUi(StatefulScreen)
              val stateless = factories.createUi(StatelessScreen)
              val capturedStateful = factories.createUi(CapturedStatefulScreen)
              val capturedStateless = factories.createUi(CapturedStatelessScreen)
              check(factories.all { it.create(UnknownScreen) == null })
              moleculeFlow(RecompositionMode.Immediate) {
                stateful.Content(State("forwarded"), TestModifier)
                stateless.Content(State("unused"), TestModifier)
                capturedStateful.Content(State("captured"), TestModifier)
                capturedStateless.Content(State("unused"), TestModifier)
              }.first()
              check(uncapturedCalls == listOf("stateful:forwarded:true", "stateless:true"))
              check(graph.recorder.calls == listOf("stateful:captured:true", "stateless:true"))
              println("subui-native:OK")
            }

            @Suppress("UNCHECKED_CAST")
            private fun Set<SubUiFactory>.createUi(screen: SubScreen<*>): SubUi<State> {
              return mapNotNull { it.create(screen) }.single() as SubUi<State>
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )
      }

    val nativeTaskSuffix =
      KmpTarget.NATIVE_HOST.gradleTargetName.replaceFirstChar { it.titlecase() }
    val result = build(fixture.gradleProject.rootDir, ":runDebugExecutable$nativeTaskSuffix")
    assertThat(result.task(":linkDebugExecutable$nativeTaskSuffix")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).contains("subui-native:OK")
  }
}
