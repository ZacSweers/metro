// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.validation

import java.io.File
import java.io.Serializable
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * A resolved runtime artifact's identity and contents. Resolution supplies only these values to the
 * task, so execution can reuse the configuration cache without retaining Gradle model objects.
 */
internal data class MetroValidationArtifact(
  @get:Input val componentId: String,
  @get:Input val displayName: String,
  @get:Internal val file: File,
) : Serializable

/**
 * Checks runtime artifacts hidden from a compilation. The report is written before validation
 * fails. Successful checks can be cached. Gradle retries failed checks on the next build.
 */
@CacheableTask
internal abstract class CheckHiddenMetroDependenciesTask : DefaultTask() {
  /** Component keys visible to the compilation. They use the same encoding as runtime artifacts. */
  @get:Input abstract val compileComponentIds: SetProperty<String>

  /** Runtime identities stay beside their files when Gradle snapshots these nested inputs. */
  @get:Nested abstract val runtimeArtifacts: ListProperty<MetroValidationArtifact>

  /**
   * Keeps the artifact view's producer tasks and classpath fingerprints. Plain report records
   * cannot carry Gradle's task dependencies themselves.
   */
  @get:Classpath abstract val runtimeFiles: ConfigurableFileCollection

  /** Empty selects every hint. Values use Kotlin's ClassId string format. */
  @get:Input abstract val scopes: SetProperty<String>

  /** Metro hints plus the formats of each enabled interop. */
  @get:Input abstract val hintFormats: SetProperty<HintFormat>

  /** The first resolved path to each runtime component, keyed by its stable component identity. */
  @get:Input abstract val runtimeDependencyPaths: MapProperty<String, String>

  /** A clean report has zero bytes. Findings are also reported as a build failure. */
  @get:OutputFile abstract val reportFile: RegularFileProperty

  init {
    group = "verification"
    scopes.convention(emptySet())
    hintFormats.convention(setOf(HintFormat.METRO))
    runtimeDependencyPaths.convention(emptyMap())
  }

  @TaskAction
  fun check() {
    val compileIds = compileComponentIds.get()
    val selectedScopes = scopes.get()
    val formats = hintFormats.get()
    val paths = runtimeDependencyPaths.get()
    val hidden =
      runtimeArtifacts.get().filter { it.componentId !in compileIds }.groupBy { it.componentId }
    val output = reportFile.get().asFile
    output.parentFile.mkdirs()
    output.bufferedWriter().use { writer ->
      for ((componentId, artifacts) in hidden.toSortedMap()) {
        val hints = sortedSetOf<String>()
        for (artifact in artifacts) {
          hints += MetroHintScanner.findHints(artifact.file, selectedScopes, formats)
        }
        if (hints.isEmpty()) {
          continue
        }
        writer.appendLine("Hidden Metro contribution: ${artifacts.first().displayName}")
        val path = paths[componentId]
        if (path != null) {
          writer.appendLine("  Path: $path")
        }
        if (selectedScopes.isNotEmpty()) {
          writer.appendLine("  Scope filter: ${selectedScopes.sorted().joinToString()}")
        }
        for (hint in hints) {
          writer.appendLine("  Hint: $hint")
        }
        writer.appendLine("  Declare this dependency directly in the consuming compilation.")
        writer.appendLine()
      }
    }
    val report = output.readText()
    if (report.isNotEmpty()) {
      throw GradleException(
        "Hidden Metro dependencies are missing from the compile classpath:\n\n" +
          report +
          "Report: ${output.absolutePath}"
      )
    }
  }
}
