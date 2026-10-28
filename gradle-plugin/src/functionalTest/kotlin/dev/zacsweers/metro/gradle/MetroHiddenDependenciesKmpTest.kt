// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("FunctionName")

package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.GradleBuilder.buildAndFail
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.gradle.BuildScript
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Each target owns its report even when its hints and dependency edges come from commonJvmMain. */
class MetroHiddenDependenciesKmpTest {

  @Test
  fun `test compilation uses main contributions without hidden dependency findings`() {
    val project = DesktopTestProject().gradleProject
    val checkTask = ":checkDesktopTestMetroHiddenDependencies"
    val report = project.rootDir.resolve("build/reports/metro/desktop/test/hidden-dependencies.txt")
    val options = arrayOf("--isolated-projects", "--console=plain")

    val tests = build(project.rootDir, ":desktopTest", *options)
    assertThat(tests.task(":desktopTest")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val checked = build(project.rootDir, checkTask, *options)
    assertThat(checked.task(checkTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(report.readText()).isEmpty()

    val automaticOptions =
      arrayOf(
        ":compileTestKotlinDesktop",
        "-Pmetro.hiddenDependencies.checkOnCompile=true",
        *options,
      )
    val automatic = build(project.rootDir, *automaticOptions)
    assertThat(automatic.task(":compileTestKotlinDesktop")?.outcome)
      .isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(automatic.task(checkTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(report.readText()).isEmpty()

    val cached = build(project.rootDir, *automaticOptions)
    assertThat(cached.output).contains("Reusing configuration cache")
    assertThat(cached.task(checkTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `runtime-only files named main remain hidden from test compilations`() {
    val project = DesktopTestProject().gradleProject
    val checkTask = ":checkDesktopTestMetroHiddenDependencies"
    val options = arrayOf("--isolated-projects", "--console=plain")
    build(project.rootDir, ":desktopTest", *options)

    // This runtime-only copy has the same basename as the associated output.
    val mainOutput = project.rootDir.resolve("build/classes/kotlin/desktop/main")
    val hiddenOutput = project.rootDir.resolve("runtime-only/main")
    assertThat(mainOutput.copyRecursively(hiddenOutput)).isTrue()
    project.rootDir
      .resolve("build.gradle.kts")
      .appendText(
        """

        kotlin.sourceSets.named("desktopTest") {
          dependencies {
            runtimeOnly(files("runtime-only/main"))
          }
        }
        """
          .trimIndent(),
      )

    val result = buildAndFail(project.rootDir, checkTask, *options)
    assertThat(result.task(checkTask)?.outcome).isEqualTo(TaskOutcome.FAILED)
    val report =
      project.rootDir.resolve("build/reports/metro/desktop/test/hidden-dependencies.txt").readText()
    assertThat(report).contains("Hidden Metro contribution: main")
    assertThat(report).contains("metro/hints/TestMainBindingsDev_zacsweers_metro_AppScopeKt.class")
  }

  @Test
  fun `commonJvm hints are inherited by JVM and Android and api exposure clears both reports`() {
    val project = CommonJvmProject().gradleProject
    val jvmTask = ":app:checkJvmMainMetroHiddenDependencies"
    val androidTask = ":app:checkAndroidMainMetroHiddenDependencies"
    val options = arrayOf("--isolated-projects", "--console=plain")

    val jvm = buildAndFail(project.rootDir, jvmTask, *options)
    assertThat(jvm.task(jvmTask)?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(jvm.task(":impl:compileKotlinJvm")).isNotNull()
    assertThat(jvm.task(androidTask)).isNull()
    assertThat(jvm.tasks.map { it.path }.filter { it.startsWith(":app:compile") }).isEmpty()
    assertThat(jvm.tasks.map { it.path }.filter { "compile" in it && "Android" in it }).isEmpty()
    val jvmReport = project.report("jvm")
    assertThat(jvmReport).contains("project ':impl'")
    assertThat(jvmReport).contains("project ':bridge'")

    val android = buildAndFail(project.rootDir, androidTask, *options)
    assertThat(android.task(androidTask)?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(
        android.tasks.map { it.path }.filter { it.startsWith(":impl:compile") && "Android" in it },
      )
      .isNotEmpty()
    assertThat(android.task(jvmTask)).isNull()
    assertThat(android.task(":impl:compileKotlinJvm")).isNull()
    assertThat(android.tasks.map { it.path }.filter { it.startsWith(":app:compile") }).isEmpty()
    assertThat(project.report("android")).contains("project ':impl'")
    assertThat(project.report("android")).contains("project ':bridge'")
    assertThat(project.report("jvm")).isEqualTo(jvmReport)

    // One shared source-set edit should expose the same module in both target variants.
    val bridgeScript = project.rootDir.resolve("bridge/build.gradle.kts")
    bridgeScript.writeText(
      bridgeScript
        .readText()
        .replace("implementation(project(\":impl\"))", "api(project(\":impl\"))"),
    )

    val exposed = build(project.rootDir, jvmTask, androidTask, *options)
    assertThat(exposed.task(jvmTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(exposed.task(androidTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.report("jvm")).doesNotContain("project ':impl'")
    assertThat(project.report("android")).doesNotContain("project ':impl'")
    assertThat(exposed.tasks.map { it.path }.filter { it.startsWith(":app:compile") }).isEmpty()
    assertThat(exposed.output).contains("Configuration cache entry stored")

    val cached = build(project.rootDir, jvmTask, androidTask, *options)
    assertThat(cached.output).contains("Reusing configuration cache")
    assertThat(cached.task(jvmTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(cached.task(androidTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(cached.tasks.map { it.path }.filter { it.startsWith(":app:compile") }).isEmpty()
  }

  /** Reports use target paths because both targets have a main compilation. */
  private fun GradleProject.report(target: String): String =
    rootDir.resolve("app/build/reports/metro/$target/main/hidden-dependencies.txt").readText()

  /** The test graph consumes a binding from its associated main compilation in the same project. */
  private class DesktopTestProject :
    MetroProject(
      reportsEnabled = false,
      additionalGradleProperties =
        listOf(
          "org.gradle.configuration-cache=true",
          "org.gradle.configuration-cache.read-only=false",
        ),
    ) {
    override fun multiplatformTargetsBlock(): String =
      """
      kotlin {
        jvm("desktop")
        sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
      }
      """
        .trimIndent() + "\n"

    override fun sources() =
      listOf(
        dev.zacsweers.metro.gradle.source(
          """
          @ContributesTo(AppScope::class)
          interface MainBindings {
            @Provides fun message(): String = "from main"
          }
          """,
          sourceSet = "desktopMain",
        ),
        dev.zacsweers.metro.gradle.source(
          """
          import kotlin.test.Test
          import kotlin.test.assertEquals

          @DependencyGraph(AppScope::class)
          interface TestGraph {
            val text: String
          }

          class MainBindingTest {
            @Test fun seesMainBinding() {
              assertEquals("from main", createGraph<TestGraph>().text)
            }
          }
          """,
          sourceSet = "desktopTest",
        ),
      )
  }

  /** The shared group has the only edge to impl and the only hint sources. */
  private class CommonJvmProject :
    MetroProject(
      multiplatform = true,
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
      return newGradleProjectBuilder(DslKind.KOTLIN)
        .withRootProject {
          withMetroSettings()
          withFile("local.properties", "sdk.dir=$sdkDir")
        }
        .withSubproject("app") {
          // Validation must still run when the consumer itself cannot compile.
          sources += source("class Consumer(val value: MissingFromConsumerClasspath)")
          withBuildScript {
            applyKmpDefaults("test.app", "implementation(project(\":bridge\"))")
          }
        }
        .withSubproject("bridge") {
          sources += source("class Bridge")
          withBuildScript {
            applyKmpDefaults("test.bridge", "implementation(project(\":impl\"))")
          }
        }
        .withSubproject("impl") {
          sources +=
            dev.zacsweers.metro.gradle.source(
              """
              object Scopes {
                abstract class SessionScope
              }

              @ContributesTo(Scopes.SessionScope::class)
              interface SessionBindings
              """,
              sourceSet = "commonJvmMain",
            )
          withBuildScript { applyKmpDefaults("test.impl") }
        }
        .write()
    }

    /** Keep dependencies in the group so neither leaf target declares them independently. */
    private fun BuildScript.Builder.applyKmpDefaults(
      namespace: String,
      commonJvmDependency: String = "",
    ) {
      plugins(GradlePlugins.Kotlin.multiplatform(), GradlePlugins.agpKmp, GradlePlugins.metro)
      withKotlin(
        """
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        kotlin {
          android {
            namespace = "$namespace"
            minSdk = 36
            compileSdk = ${System.getProperty("metro.androidCompileSdk")}
          }
          jvm()

          applyDefaultHierarchyTemplate {
            common {
              group("commonJvm") {
                // Include the Android target registered by the AGP multiplatform plugin.
                withCompilations { it.target.name == "android" }
                withJvm()
              }
            }
          }
          sourceSets {
            named("commonJvmMain") {
              dependencies {
                $commonJvmDependency
              }
            }
          }
        }

        ${buildMetroBlock()}

        @OptIn(dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class)
        metro {
          hiddenDependencies {
            scopes.add("test/Scopes.SessionScope")
          }
        }
        """
          .trimIndent(),
      )
    }
  }
}
