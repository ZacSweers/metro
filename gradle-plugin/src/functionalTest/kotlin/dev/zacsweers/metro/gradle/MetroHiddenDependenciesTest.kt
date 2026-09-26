// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("FunctionName")

package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.GradleBuilder.buildAndFail
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.gradle.BuildScript
import com.autonomousapps.kit.gradle.Dependency
import com.autonomousapps.kit.gradle.Plugin
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises validation through Gradle's selected variants and actual compiler-generated hints. */
class MetroHiddenDependenciesTest {

  @Test
  fun `default JVM check reports hidden hints without compiling its consumer`() {
    val project = HiddenDependenciesProject().gradleProject

    val result =
      buildAndFail(
        project.rootDir,
        ":checkMainMetroHiddenDependencies",
        "--isolated-projects",
        "--console=plain",
      )

    assertThat(result.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(result.task(":impl:compileKotlin")).isNotNull()
    assertThat(result.task(":compileKotlin")).isNull()
    assertThat(result.task(":compileTestKotlin")).isNull()

    val report = project.hiddenDependenciesReport()
    assertThat(report).contains("project ':impl'")
    assertThat(report).doesNotContain("project ':plain'")
    assertThat(result.output).contains("Configuration cache entry stored")
  }

  @Test
  fun `api exposure passes and reuses configuration cache with isolated projects`() {
    val project = HiddenDependenciesProject(exposeImplementation = true).gradleProject
    val arguments =
      arrayOf(":checkMainMetroHiddenDependencies", "--isolated-projects", "--console=plain")

    val result = build(project.rootDir, *arguments)
    assertThat(result.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":compileKotlin")).isNull()
    assertThat(project.hiddenDependenciesReport()).doesNotContain("project ':impl'")
    assertThat(result.output).contains("Configuration cache entry stored")

    val cached = build(project.rootDir, *arguments)
    assertThat(cached.output).contains("Reusing configuration cache")
    assertThat(cached.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(cached.task(":compileKotlin")).isNull()
  }

  @Test
  fun `scope ClassIds select nested scopes and invalidate cached inputs`() {
    val project = HiddenDependenciesProject(filterByGradleProperty = true).gradleProject
    val arguments =
      arrayOf(":checkMainMetroHiddenDependencies", "--isolated-projects", "--console=plain")
    val excludedScope = "-PmetroTestScope=test/OtherScope"

    val excluded = build(project.rootDir, *arguments, excludedScope)
    assertThat(excluded.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.hiddenDependenciesReport()).doesNotContain("project ':impl'")
    assertThat(excluded.output).contains("Configuration cache entry stored")

    val cached = build(project.rootDir, *arguments, excludedScope)
    assertThat(cached.output).contains("Reusing configuration cache")
    assertThat(cached.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.UP_TO_DATE)

    val included =
      buildAndFail(project.rootDir, *arguments, "-PmetroTestScope=test/Scopes.SessionScope")
    assertThat(included.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(project.hiddenDependenciesReport()).contains("project ':impl'")
    assertThat(included.task(":compileKotlin")).isNull()
  }

  @Test
  fun `KMP JVM check scans only the selected target`() {
    val project = HiddenDependenciesProject(kmp = true).gradleProject

    val result =
      buildAndFail(project.rootDir, ":checkJvmMainMetroHiddenDependencies", "--console=plain")

    assertThat(result.task(":checkJvmMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(result.task(":impl:compileKotlinJvm")).isNotNull()
    assertThat(result.task(":compileKotlinJvm")).isNull()
    assertThat(result.tasks.map { it.path }.filter { "compile" in it && "Js" in it }).isEmpty()
    assertThat(project.hiddenDependenciesReport("jvm/main")).contains("project ':impl'")
  }

  @Test
  fun `Android check follows debug dependencies and keeps release isolated`() {
    val project = AndroidHiddenDependenciesProject().gradleProject
    val debug =
      buildAndFail(
        project.rootDir,
        ":app:checkDebugMetroHiddenDependencies",
        "--isolated-projects",
        "--console=plain",
      )

    assertThat(debug.task(":app:checkDebugMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(debug.tasks.map { it.path }.filter { it.startsWith(":app:compile") }).isEmpty()
    assertThat(debug.task(":app:checkReleaseMetroHiddenDependencies")).isNull()
    assertThat(debug.task(":app:checkDebugUnitTestMetroHiddenDependencies")).isNull()
    val app = project.rootDir.resolve("app")
    val debugReport = app.resolve("build/reports/metro/debug/hidden-dependencies.txt").readText()
    assertThat(debugReport).contains("project ':impl'")

    val release =
      build(
        project.rootDir,
        ":app:checkReleaseMetroHiddenDependencies",
        "--isolated-projects",
        "--console=plain",
      )
    assertThat(release.task(":app:checkReleaseMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(release.tasks.map { it.path }.filter { it.startsWith(":app:compile") }).isEmpty()
    assertThat(release.task(":app:checkDebugMetroHiddenDependencies")).isNull()
    assertThat(app.resolve("build/reports/metro/release/hidden-dependencies.txt").readText())
      .doesNotContain("project ':impl'")
    assertThat(app.resolve("build/reports/metro/debug/hidden-dependencies.txt").readText())
      .isEqualTo(debugReport)
  }

  @Test
  fun `Android check finds hidden JVM jars and excludes directly visible JVM jars`() {
    val project = AndroidHiddenDependenciesProject(jvmImplementation = true).gradleProject
    val debug =
      buildAndFail(
        project.rootDir,
        ":app:checkDebugMetroHiddenDependencies",
        "--isolated-projects",
        "--console=plain",
      )

    assertThat(debug.task(":app:checkDebugMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(debug.task(":impl:compileKotlin")).isNotNull()
    assertThat(debug.task(":visible:compileKotlin")).isNull()
    assertThat(debug.tasks.map { it.path }.filter { it.startsWith(":app:compile") }).isEmpty()
    val app = project.rootDir.resolve("app")
    val debugReport = app.resolve("build/reports/metro/debug/hidden-dependencies.txt").readText()
    assertThat(debugReport).contains("project ':impl'")
    assertThat(debugReport).doesNotContain("project ':visible'")

    // Release still resolves visible's hint-bearing JVM jar through Android's artifact views.
    val release =
      build(
        project.rootDir,
        ":app:checkReleaseMetroHiddenDependencies",
        "--isolated-projects",
        "--console=plain",
      )
    assertThat(release.task(":app:checkReleaseMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(app.resolve("build/reports/metro/release/hidden-dependencies.txt").readText())
      .doesNotContain("project ':visible'")
  }

  /**
   * Only bridge's debug variant carries impl. The JVM fixture also exposes a hint-bearing jar
   * directly to app. Android's artifact views must recognise both kinds of JVM dependency.
   */
  private class AndroidHiddenDependenciesProject(private val jvmImplementation: Boolean = false) :
    MetroProject(
      multiplatform = false,
      reportsEnabled = false,
      additionalGradleProperties =
        listOf(
          "org.gradle.configuration-cache=true",
          "org.gradle.configuration-cache.read-only=false",
        ),
    ) {
    override fun buildGradleProject(): GradleProject {
      val androidHome = System.getProperty("metro.androidHome")
      assumeTrue(androidHome != null)
      val sdkDir = File(androidHome).invariantSeparatorsPath
      val project =
        newGradleProjectBuilder(DslKind.KOTLIN)
          .withRootProject {
            withMetroSettings()
            withFile("local.properties", "sdk.dir=$sdkDir")
          }
          .withSubproject("app") {
            sources += source("class Consumer(val value: MissingFromConsumerClasspath)")
            withBuildScript {
              applyAndroidDefaults("com.android.application", "test.app")
              if (jvmImplementation) {
                dependencies(
                  Dependency.implementation(":bridge"),
                  Dependency.implementation(":visible"),
                )
              } else {
                dependencies(Dependency.implementation(":bridge"))
              }
            }
          }
          .withSubproject("bridge") {
            sources += source("class Bridge")
            withBuildScript {
              applyAndroidDefaults("com.android.library", "test.bridge")
              dependencies(
                Dependency.implementation(":impl").copy(configuration = "debugImplementation")
              )
            }
          }
          .withSubproject("impl") {
            sources +=
              source(
                """
                abstract class AppScope

                @ContributesTo(AppScope::class)
                interface AppBindings
                """
              )
            withBuildScript {
              if (jvmImplementation) {
                applyMetroDefault()
              } else {
                applyAndroidDefaults("com.android.library", "test.impl")
              }
            }
          }
          .apply {
            if (jvmImplementation) {
              withSubproject("visible") {
                sources +=
                  source(
                    """
                    abstract class VisibleScope

                    @ContributesTo(VisibleScope::class)
                    interface VisibleBindings
                    """
                  )
                withBuildScript { applyMetroDefault() }
              }
            }
          }
          .write()
      val androidModules =
        if (jvmImplementation) {
          listOf("app", "bridge")
        } else {
          listOf("app", "bridge", "impl")
        }
      for (module in androidModules) {
        project.rootDir.resolve("$module/src/main/AndroidManifest.xml").writeText("<manifest />")
      }
      return project
    }

    private fun BuildScript.Builder.applyAndroidDefaults(pluginId: String, namespace: String) {
      plugins(
        Plugin(pluginId, System.getProperty("metro.agpVersion")),
        // Parcelize registers Metro with AGP's built-in Kotlin compilation support.
        Plugin("org.jetbrains.kotlin.plugin.parcelize", getTestCompilerVersion()),
        GradlePlugins.metro,
      )
      withKotlin(
        """
        android {
          namespace = "$namespace"
          compileSdk = ${System.getProperty("metro.androidCompileSdk")}
        }

        ${buildMetroBlock()}
        """
          .trimIndent()
      )
    }
  }

  /** The hidden edge and the scope input are controlled without changing the consumer's sources. */
  private class HiddenDependenciesProject(
    private val exposeImplementation: Boolean = false,
    private val filterByGradleProperty: Boolean = false,
    kmp: Boolean = false,
  ) :
    MetroProject(
      multiplatform = kmp,
      reportsEnabled = false,
      additionalGradleProperties =
        listOf(
          "org.gradle.configuration-cache=true",
          "org.gradle.configuration-cache.read-only=false",
        ),
    ) {

    override fun multiplatformTargetsBlock() =
      """
      kotlin {
        jvm()
        js { nodejs() }
      }
      """
        .trimIndent()

    override fun StringBuilder.onBuildScript() {
      if (filterByGradleProperty) {
        appendLine(
          """
          @OptIn(dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class)
          metro {
            aggregationScopes.addAll(
              providers.gradleProperty("metroTestScope").map { listOf(it) }.orElse(emptyList())
            )
          }
          """
            .trimIndent()
        )
      }
    }

    override fun buildGradleProject() = multiModuleProject {
      root {
        dependencies(Dependency.implementation(":bridge"))
        // An accidental dependency on the consumer compile task makes validation fail here.
        sources(source("class Consumer(val value: MissingFromConsumerClasspath)"))
      }
      subproject("bridge") {
        dependencies(
          if (exposeImplementation) {
            Dependency.api(":impl")
          } else {
            Dependency.implementation(":impl")
          },
          Dependency.implementation(":plain"),
        )
        sources(
          source(
            """
            abstract class BridgeScope

            @ContributesTo(BridgeScope::class)
            interface BridgeBindings
            """
          )
        )
      }
      subproject("impl") {
        sources(
          source(
            """
            abstract class AppScope
            abstract class Scopes {
              abstract class SessionScope
            }

            @ContributesTo(AppScope::class)
            interface AppBindings

            @ContributesTo(Scopes.SessionScope::class)
            interface SessionBindings
            """
          )
        )
      }
      subproject("plain") { sources(source("class OrdinaryDependency")) }
    }
  }

  private fun GradleProject.hiddenDependenciesReport(compilationPath: String = "main"): String =
    rootDir.resolve("build/reports/metro/$compilationPath/hidden-dependencies.txt").readText()
}
