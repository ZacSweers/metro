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
import dev.zacsweers.metro.gradle.incremental.BaseIncrementalCompilationTest
import dev.zacsweers.metro.gradle.source as testSource
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CircuitWebTest(target: KmpTarget, private val generateClassesInIr: Boolean) :
  BaseIncrementalCompilationTest(target) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}, generateClassesInIr={1}")
    fun parameters(): List<Array<Any>> =
      listOf(KmpTarget.JS, KmpTarget.WASM_JS).flatMap { target ->
        listOf(true, false).map { arrayOf<Any>(target, it) }
      }
  }

  // https://github.com/ZacSweers/metro/issues/2904
  @Test
  fun subUiFunctionFactoriesCompileAfterAnEdit() {
    assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.3.20"))
    if (generateClassesInIr) {
      assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.20-dev-6138"))
    }

    val composePlugin = Plugin("org.jetbrains.kotlin.plugin.compose", getTestCompilerVersion())
    val circuit = implementation("com.slack.circuit:circuitx-subcircuit:${getTestCircuitVersion()}")
    val fixture =
      object :
        MetroProject(additionalGradleProperties = listOf("kotlin.build.report.output=file")) {
        val uiFunctions =
          testSource(
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
            sourceSet = "${target.gradleTargetName}Main",
          )

        private val app =
          testSource(
            $$"""
            import app.cash.molecule.RecompositionMode
            import app.cash.molecule.moleculeFlow
            import com.slack.circuit.subcircuit.SubScreen
            import com.slack.circuit.subcircuit.SubUi
            import com.slack.circuit.subcircuit.SubUiFactory
            import kotlinx.coroutines.flow.first

            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val uiFactories: Set<SubUiFactory>
              val recorder: Recorder
            }

            suspend fun main() {
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
              println("subui-web:${uncapturedCalls.joinToString()}|${graph.recorder.calls.joinToString()}")
            }

            @Suppress("UNCHECKED_CAST")
            private fun Set<SubUiFactory>.createUi(screen: SubScreen<*>): SubUi<State> {
              return mapNotNull { it.create(screen) }.single() as SubUi<State>
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
            sourceSet = "${target.gradleTargetName}Main",
          )

        override fun multiplatformTargetsBlock(): String =
          """
          kotlin {
            @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
            ${target.gradleTargetName} {
              nodejs()
              binaries.executable()
            }
          }
          """
            .trimIndent() + "\n"

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(uiFunctions, app)
            dependencies(
              circuit,
              implementation("org.jetbrains.compose.ui:ui:1.13.0-alpha01"),
              implementation("app.cash.molecule:molecule-runtime:2.2.0"),
            )
            buildScript {
              plugins(GradlePlugins.Kotlin.multiplatform(), composePlugin, GradlePlugins.metro)
              withKotlin(
                multiplatformTargetsBlock() +
                  buildMetroBlock() +
                  """
                  @OptIn(dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class)
                  metro {
                    enableCircuitCodegen.set(true)
                    generateClassesInIr.set($generateClassesInIr)
                  }
                  """
                    .trimIndent(),
              )
            }
          }
        }
      }

    val project = fixture.gradleProject
    val compileTask = ":${target.compileTaskName}"
    val executableTask =
      if (target == KmpTarget.JS) {
        ":jsNodeProductionRun"
      } else {
        ":compileProductionExecutableKotlinWasmJs"
      }
    val firstBuild = build(project.rootDir, compileTask, executableTask)
    assertThat(firstBuild.task(compileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(firstBuild.task(executableTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    if (target == KmpTarget.JS) {
      assertThat(firstBuild.output)
        .contains(
          "subui-web:stateful:forwarded:true, stateless:true|stateful:captured:true, stateless:true",
        )
    }

    val uiFile = project.rootDir.resolve("src/${target.gradleTargetName}Main/kotlin/test/SubUis.kt")
    uiFile.writeText(uiFile.readText().replace("stateful:", "updated:"))
    val reportsDir = project.rootDir.resolve("build/reports/kotlin-build")
    val previousReports = reportsDir.listFiles().orEmpty().toSet()
    val secondBuild = build(project.rootDir, compileTask, executableTask)
    assertThat(secondBuild.task(compileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(secondBuild.task(executableTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val report = (reportsDir.listFiles().orEmpty().toSet() - previousReports).single()
    assertThat(compiledSourceNames(report, compileTask)).contains("SubUis.kt")
    if (target == KmpTarget.JS) {
      assertThat(secondBuild.output)
        .contains(
          "subui-web:updated:forwarded:true, stateless:true|updated:captured:true, stateless:true",
        )
    }
  }
}
