// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS")

package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestCompilerVersion
import dev.zacsweers.metro.gradle.resolveSafe
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Reproduces JS/Wasm IC failures when a source owning a top-level FIR hint is deleted. */
@RunWith(Parameterized::class)
class FirHintDeletionICTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
  }

  @Test
  fun deletedContributorMatchesCleanBuildWithoutIcFallback() {
    assumeTrue(
      "This KLIB reproducer covers JS and Wasm",
      target in setOf(KmpTarget.JS, KmpTarget.WASM_JS),
    )
    val fixture =
      object :
        MetroProject(
          additionalGradleProperties =
            listOf(
              "kotlin.incremental=true",
              "kotlin.build.report.output=file",
              "kotlin.kmp.separateCompilation=false",
            )
        ) {
        private val keep = source("interface Kept", "Keep", includeDefaultImports = false)
        val appModule =
          source(
            "@ContributesTo(Unit::class) interface AppModule",
            "AppModule",
            "test",
            false,
            "dev.zacsweers.metro.ContributesTo",
          )

        override fun sources() = listOf(keep, appModule)

        // A second platform makes the sources genuinely common to both Web compilations.
        override fun multiplatformTargetsBlock() =
          """
          kotlin {
            jvm()
            js { nodejs() }
            wasmJs { nodejs() }
          }
          """
            .trimIndent()
      }

    val project = fixture.gradleProject
    val task = compileTaskFor()
    val initial = project.compileKotlin()
    assertThat(initial.task(task)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.hintMetadata().keys).isNotEmpty()

    val reports = project.rootDir.resolveSafe("build/reports/kotlin-build")
    val previousReports = reports.listFiles().orEmpty().toSet()
    project.delete(fixture.appModule)
    // Even if IC fails the Gradle task, run the clean control on the same remaining source.
    val incremental =
      try {
        project.compileKotlin()
      } catch (failure: UnexpectedBuildFailure) {
        failure.buildResult
      }
    val incrementalHints = project.hintMetadata()
    val removalReports = reports.listFiles().orEmpty().toSet() - previousReports
    val reportText = removalReports.joinToString("\n") { it.readText() }

    val clean = project.compileKotlin("clean", false, task)
    assertThat(clean.task(task)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val cleanHints = project.hintMetadata()
    assertWithMessage("The clean build should contain only Kept").that(cleanHints).isEmpty()

    val context =
      "Kotlin ${getTestCompilerVersion()} / $target\n" +
        "Fixture: ${project.rootDir}\n" +
        "After deletion: ${incremental.task(task)?.outcome}\n" +
        "IC hint files: ${incrementalHints.keys}\n" +
        "Clean hint files: ${cleanHints.keys}\n" +
        "Deletion output:\n${incremental.output}\nKotlin report:\n$reportText"
    assertWithMessage(context).that(incremental.task(task)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertWithMessage(context).that(removalReports).isNotEmpty()
    assertWithMessage(context).that(incrementalHints).containsExactlyEntriesIn(cleanHints)
    assertWithMessage(context).that(reportText).doesNotContain("IC_FAILED_TO_COMPILE_INCREMENTALLY")
    assertWithMessage(context)
      .that(reportText)
      .doesNotContain("REBUILD_REASON: Failed to compile incrementally")
  }

  /**
   * Keep hint metadata in memory because the control build deletes the fixture's build directory.
   */
  private fun GradleProject.hintMetadata(): Map<String, List<Byte>> {
    val hints =
      rootDir.resolve(
        "build/classes/kotlin/${target.gradleTargetName}/main/default/linkdata/package_metro.hints"
      )
    return hints
      .walkTopDown()
      .filter { it.isFile && it.extension == "knm" }
      .associate { it.relativeTo(hints).invariantSeparatorsPath to it.readBytes().toList() }
  }
}
