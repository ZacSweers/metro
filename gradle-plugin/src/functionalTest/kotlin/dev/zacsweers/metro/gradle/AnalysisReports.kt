// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import java.io.File
import java.nio.file.Path
import kotlin.io.path.div
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * Provides access to Metro's analysis reports for a project.
 *
 * Use [Project.metroAnalysisReports] to get an instance for a project, or [AnalysisReports.from]
 * for file-based access (useful in tests).
 */
interface AnalysisReports {

  /**
   * Base directory containing all Metro reports.
   *
   * The structure is: `{buildDir}/reports/metro/`
   */
  val reportsDir: Provider<Directory>

  companion object {
    /**
     * Creates an [AnalysisReports] instance from a project root directory.
     *
     * This is useful for tests that run Gradle externally and need file-based access to reports.
     *
     * Example usage:
     * ```kotlin
     * val reports = AnalysisReports.from(project.rootDir)
     * val metadataFile = reports.graphMetadataFile
     * ```
     */
    fun from(projectRootDir: File): PathBasedAnalysisReports =
      PathBasedAnalysisReports(projectRootDir.toPath().resolve("build/reports/metro"))
  }
}

/**
 * Path-based implementation of analysis reports access.
 *
 * Provides direct [Path] access to report files, useful for tests that run Gradle externally.
 */
class PathBasedAnalysisReports(val reportsDir: Path) {

  /** The aggregated graph metadata JSON file. */
  val graphMetadataFile: Path
    get() = reportsDir / "graphMetadata.json"

  /** The analysis results JSON file. */
  val analysisFile: Path
    get() = reportsDir / "analysis.json"

  /** Directory containing interactive HTML visualizations. */
  val htmlDirectory: Path
    get() = reportsDir / "html"

  /** Returns an HTML file for a specific graph. */
  fun htmlFileForGraph(graphName: String): Path =
    htmlDirectory / "${graphName.replace('.', '-')}.html"
}

/**
 * Returns an [AnalysisReports] instance for accessing Metro analysis output files.
 *
 * Example usage:
 * ```kotlin
 * val reports = project.metroAnalysisReports()
 * val htmlDir = reports.htmlDirectory.get().asFile
 * ```
 */
fun Project.metroAnalysisReports(): AnalysisReports =
  object : AnalysisReports {
    override val reportsDir: Provider<Directory> = layout.buildDirectory.dir("reports/metro")
  }

/**
 * The aggregated graph metadata JSON file.
 *
 * Contains machine-readable metadata for all dependency graphs in the project, including bindings,
 * dependencies, scopes, and roots.
 *
 * Location: `{reportsDir}/graphMetadata.json`
 */
val AnalysisReports.graphMetadataFile: Provider<RegularFile>
  get() = reportsDir.map { it.file("graphMetadata.json") }

/**
 * The analysis results JSON file.
 *
 * Contains comprehensive analysis results including statistics, longest paths, dominators,
 * centrality scores, and fan analysis for all dependency graphs.
 *
 * Location: `{reportsDir}/analysis.json`
 */
val AnalysisReports.analysisFile: Provider<RegularFile>
  get() = reportsDir.map { it.file("analysis.json") }

/**
 * Directory containing interactive HTML visualizations.
 *
 * Contains one HTML file per dependency graph with interactive ECharts visualizations, plus an
 * index page linking to all graphs.
 *
 * Location: `{reportsDir}/html/`
 */
val AnalysisReports.htmlDirectory: Provider<Directory>
  get() = reportsDir.map { it.dir("html") }
