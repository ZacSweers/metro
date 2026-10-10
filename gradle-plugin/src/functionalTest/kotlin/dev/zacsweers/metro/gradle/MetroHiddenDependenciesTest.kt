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
  fun `compilation does not check hidden dependencies by default`() {
    val project = HiddenDependenciesProject(consumerCompiles = true).gradleProject

    val result = build(project.rootDir, ":compileKotlin", "--isolated-projects", "--console=plain")

    assertThat(result.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.tasks.map { it.path }.filter { "MetroHiddenDependencies" in it }).isEmpty()
  }

  @Test
  fun `automatic check reports hidden contributions after successful compilation`() {
    val project =
      HiddenDependenciesProject(checkOnCompile = true, consumerCompiles = true).gradleProject

    val result =
      buildAndFail(project.rootDir, ":compileKotlin", "--isolated-projects", "--console=plain")

    assertThat(result.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(result.task(":checkTestMetroHiddenDependencies")).isNull()
    assertThat(project.hiddenDependenciesReport()).contains("project ':impl'")
    val taskPaths = result.tasks.map { it.path }
    assertThat(taskPaths.indexOf(":checkMainMetroHiddenDependencies"))
      .isGreaterThan(taskPaths.indexOf(":compileKotlin"))
  }

  @Test
  fun `automatic check reports hidden contributions after failed compilation`() {
    val project = HiddenDependenciesProject(checkOnCompile = true).gradleProject

    val result =
      buildAndFail(project.rootDir, ":compileKotlin", "--isolated-projects", "--console=plain")

    assertThat(result.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(project.hiddenDependenciesReport()).contains("project ':impl'")
  }

  @Test
  fun `automatic checks finalize main and test compilations and reuse configuration cache`() {
    val project =
      HiddenDependenciesProject(
          checkOnCompile = true,
          exposeImplementation = true,
          consumerCompiles = true,
        )
        .gradleProject
    val arguments = arrayOf(":compileTestKotlin", "--isolated-projects", "--console=plain")

    val result = build(project.rootDir, *arguments)
    assertThat(result.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":checkTestMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).contains("Configuration cache entry stored")

    val cached = build(project.rootDir, *arguments)
    assertThat(cached.output).contains("Reusing configuration cache")
    assertThat(cached.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(cached.task(":checkTestMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `automatic KMP check finalizes only the selected compilation`() {
    val project =
      HiddenDependenciesProject(
          checkOnCompile = true,
          consumerCompiles = true,
          kmp = true,
        )
        .gradleProject

    val result = buildAndFail(project.rootDir, ":compileKotlinJvm", "--console=plain")

    assertThat(result.task(":compileKotlinJvm")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":checkJvmMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(result.task(":checkJvmTestMetroHiddenDependencies")).isNull()
    assertThat(result.tasks.map { it.path }.filter { "compile" in it && "Js" in it }).isEmpty()
    assertThat(project.hiddenDependenciesReport("jvm/main")).contains("project ':impl'")
  }

  @Test
  fun `Gradle property enables automatic checks for the selected Android variant`() {
    val project = AndroidHiddenDependenciesProject().gradleProject

    val result =
      buildAndFail(
        project.rootDir,
        ":app:compileDebugKotlin",
        "-Pmetro.hiddenDependencies.checkOnCompile=true",
        "--isolated-projects",
        "--console=plain",
      )

    assertThat(result.task(":app:compileDebugKotlin")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.task(":app:checkDebugMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(result.task(":app:checkReleaseMetroHiddenDependencies")).isNull()
    assertThat(result.task(":app:checkDebugUnitTestMetroHiddenDependencies")).isNull()
    assertThat(
        project.rootDir.resolve("app/build/reports/metro/debug/hidden-dependencies.txt").readText(),
      )
      .contains("project ':impl'")
  }

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
  fun `interop hints are checked only when their interop is enabled`() {
    val project = InteropHintsProject().gradleProject
    val arguments =
      arrayOf(":checkMainMetroHiddenDependencies", "--isolated-projects", "--console=plain")

    val disabled = build(project.rootDir, *arguments)
    assertThat(disabled.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.hiddenDependenciesReport()).isEmpty()

    val otherScope =
      build(project.rootDir, *arguments, "-PmetroTestInterop", "-PmetroTestScopes=test/OtherScope")
    assertThat(otherScope.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.hiddenDependenciesReport()).isEmpty()

    val enabled =
      buildAndFail(
        project.rootDir,
        *arguments,
        "-PmetroTestInterop",
        "-PmetroTestScopes=javax/inject/Singleton",
      )
    assertThat(enabled.task(":checkMainMetroHiddenDependencies")?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(enabled.task(":compileKotlin")).isNull()
    val report = project.hiddenDependenciesReport()
    assertThat(report).contains("project ':impl'")
    assertThat(report).contains("Hint: hilt_aggregated_deps/_test_HiltModule.class")
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
            // Newer Kotlin Gradle plugins fail when subprojects load their own copies of it.
            withBuildScript {
              val agpVersion = System.getProperty("metro.agpVersion")
              val kotlinVersion = getTestCompilerVersion()
              plugins(
                Plugin(ANDROID_APPLICATION, agpVersion, apply = false),
                Plugin(ANDROID_LIBRARY, agpVersion, apply = false),
                Plugin(KOTLIN_JVM, kotlinVersion, apply = false),
                Plugin(KOTLIN_PARCELIZE, kotlinVersion, apply = false),
                Plugin(GradlePlugins.metro.id, GradlePlugins.metro.version, apply = false),
              )
            }
          }
          .withSubproject("app") {
            sources += source("class Consumer(val value: MissingFromConsumerClasspath)")
            withBuildScript {
              applyAndroidDefaults(ANDROID_APPLICATION, "test.app")
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
              applyAndroidDefaults(ANDROID_LIBRARY, "test.bridge")
              dependencies(
                Dependency.implementation(":impl").copy(configuration = "debugImplementation"),
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
                """,
              )
            withBuildScript {
              if (jvmImplementation) {
                applyJvmDefaults()
              } else {
                applyAndroidDefaults(ANDROID_LIBRARY, "test.impl")
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
                    """,
                  )
                withBuildScript { applyJvmDefaults() }
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

    private fun BuildScript.Builder.applyJvmDefaults() {
      plugins(Plugin(KOTLIN_JVM), Plugin(GradlePlugins.metro.id))
      withKotlin(buildMetroBlock())
    }

    private fun BuildScript.Builder.applyAndroidDefaults(pluginId: String, namespace: String) {
      plugins(
        Plugin(pluginId),
        // Parcelize registers Metro with AGP's built-in Kotlin compilation support.
        Plugin(KOTLIN_PARCELIZE),
        Plugin(GradlePlugins.metro.id),
      )
      withKotlin(
        """
        android {
          namespace = "$namespace"
          compileSdk = ${System.getProperty("metro.androidCompileSdk")}
        }

        ${buildMetroBlock()}
        """
          .trimIndent(),
      )
    }

    private companion object {
      const val ANDROID_APPLICATION = "com.android.application"
      const val ANDROID_LIBRARY = "com.android.library"
      const val KOTLIN_JVM = "org.jetbrains.kotlin.jvm"
      const val KOTLIN_PARCELIZE = "org.jetbrains.kotlin.plugin.parcelize"
    }
  }

  /** The hidden edge and the scope input are controlled without changing the consumer's sources. */
  private class HiddenDependenciesProject(
    private val exposeImplementation: Boolean = false,
    private val filterByGradleProperty: Boolean = false,
    private val checkOnCompile: Boolean = false,
    private val consumerCompiles: Boolean = false,
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
      if (checkOnCompile) {
        appendLine(
          """
          @OptIn(dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class)
          metro {
            hiddenDependencies {
              checkOnCompile.set(true)
            }
          }
          """
            .trimIndent(),
        )
      }
      if (filterByGradleProperty) {
        appendLine(
          """
          @OptIn(dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class)
          metro {
            hiddenDependencies {
              scopes.addAll(
                providers.gradleProperty("metroTestScope").map { listOf(it) }.orElse(emptyList())
              )
            }
          }
          """
            .trimIndent(),
        )
      }
    }

    override fun buildGradleProject() = multiModuleProject {
      root {
        dependencies(Dependency.implementation(":bridge"))
        if (consumerCompiles) {
          sources(source("class Consumer"))
        } else {
          // An accidental dependency on the consumer compile task makes validation fail here.
          sources(source("class Consumer(val value: MissingFromConsumerClasspath)"))
        }
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
            """,
          ),
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
            """,
          ),
        )
      }
      subproject("plain") { sources(source("class OrdinaryDependency")) }
    }
  }

  /**
   * A hidden dependency with hand-written Hilt metadata. Kotlin compiles the marker, so checks see
   * a real Kotlin annotation. A stub stands in for Hilt's annotation. Only the consumer enables
   * interop, so impl gets no Metro hints.
   */
  private class InteropHintsProject :
    MetroProject(
      multiplatform = false,
      reportsEnabled = false,
      additionalGradleProperties =
        listOf(
          "org.gradle.configuration-cache=true",
          "org.gradle.configuration-cache.read-only=false",
        ),
    ) {

    override fun StringBuilder.onBuildScript() {
      appendLine(
        """
        @OptIn(dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class)
        metro {
          if (path == ":" && providers.gradleProperty("metroTestInterop").isPresent) {
            interop {
              includeHilt()
            }
          }
          hiddenDependencies {
            scopes.addAll(
              providers.gradleProperty("metroTestScopes").map { it.split(",") }.orElse(emptyList())
            )
          }
        }
        """
          .trimIndent(),
      )
    }

    override fun buildGradleProject() = multiModuleProject {
      root {
        dependencies(Dependency.implementation(":bridge"))
        sources(source("class Consumer(val value: MissingFromConsumerClasspath)"))
      }
      subproject("bridge") {
        dependencies(Dependency.implementation(":impl"))
        sources(source("class Bridge"))
      }
      subproject("impl") {
        sources(
          source(
            """
            @Retention(AnnotationRetention.BINARY)
            annotation class AggregatedDeps(
              val components: Array<String>,
              val test: String = "",
              val modules: Array<String> = [],
              val entryPoints: Array<String> = [],
            )
            """,
            packageName = "dagger.hilt.processor.internal.aggregateddeps",
            includeDefaultImports = false,
          ),
          source(
            """
            @AggregatedDeps(
              components = ["dagger.hilt.components.SingletonComponent"],
              modules = ["test.HiltModule"],
            )
            class _test_HiltModule
            """,
            packageName = "hilt_aggregated_deps",
            includeDefaultImports = false,
            extraImports = arrayOf("dagger.hilt.processor.internal.aggregateddeps.AggregatedDeps"),
          ),
        )
      }
    }
  }

  private fun GradleProject.hiddenDependenciesReport(compilationPath: String = "main"): String =
    rootDir.resolve("build/reports/metro/$compilationPath/hidden-dependencies.txt").readText()
}
