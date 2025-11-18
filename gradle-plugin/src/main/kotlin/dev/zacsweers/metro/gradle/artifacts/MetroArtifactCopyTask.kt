// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.artifacts

import dev.zacsweers.metro.gradle.capitalizeUS
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation

/**
 * Copies Metro compiler reports from the Kotlin compiler output directory to a stable location.
 *
 * The Kotlin compiler generates Metro reports (graph metadata, cycle detection results, etc.) in a
 * temporary directory during compilation. This task copies those reports to a well-known location
 * in the build directory where they can be accessed by other tasks, tools, or users.
 *
 * This task implements [MetroArtifacts] to provide access to the copied reports through convenient
 * extension properties like [graphMetadataDir].
 */
@DisableCachingByDefault(because = "Its inputs don't participate in build cache")
internal abstract class MetroArtifactCopyTask : DefaultTask(), MetroArtifacts {

  /** The Gradle project path this task is generating metadata for. */
  @get:Input abstract val projectPath: Property<String>

  /** The kotlinc compilation name. */
  @get:Input abstract val compilationName: Property<String>

  /**
   * The source directory where the Kotlin compiler writes Metro reports. Must be internal because
   * there's no provider to chain from kotlinc's task.
   */
  @get:Internal abstract val kotlincReportsDir: DirectoryProperty

  /** The destination directory where reports will be copied to. */
  @get:OutputDirectory abstract override val reportsDir: DirectoryProperty

  @OptIn(ExperimentalPathApi::class)
  @TaskAction
  internal fun copy() {
    val kotlincReportsDir = kotlincReportsDir.get().asFile.toPath()

    val reportsDir =
      reportsDir.get().asFile.toPath().apply {
        if (exists()) {
          deleteRecursively()
        }
        createDirectories()
      }

    if (kotlincReportsDir.exists()) {
      kotlincReportsDir.copyToRecursively(reportsDir, overwrite = true, followLinks = false)
    } else {
      // Do nothing else, that compilation probably didn't run
    }
  }

  internal companion object Companion {
    fun taskName(sourceCompilation: KotlinCompilation<*>): String =
      "copy${sourceCompilation.compileKotlinTaskName.capitalizeUS()}MetroArtifacts"

    fun register(
      project: Project,
      reportsDir: Provider<Directory>,
      sourceCompilation: KotlinCompilation<*>,
    ): TaskProvider<out MetroArtifactCopyTask> {
      return project.tasks.register(
        taskName(sourceCompilation),
        MetroArtifactCopyTask::class.java,
      ) { task ->
        task.projectPath.set(project.path)
        task.compilationName.set(sourceCompilation.name)
        task.kotlincReportsDir.set(reportsDir)
        task.reportsDir.set(
          project.layout.buildDirectory.dir("tmp/metro/reporting/${sourceCompilation.name}")
        )
        task.dependsOn(sourceCompilation.compileTaskProvider)
      }
    }
  }
}
