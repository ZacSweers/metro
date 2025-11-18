// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.artifacts

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/**
 * Provides access to Metro's build artifacts and reports for a Kotlin compilation.
 *
 * This interface is implemented by Metro's Gradle tasks to expose various output directories
 * containing reports, metadata, and other artifacts generated during compilation.
 */
public interface MetroArtifacts {

  /**
   * Directory containing all Metro reports for this compilation.
   *
   * This is the base directory for all Metro reports (graph metadata, cycle reports, logs, etc.)
   * The structure is: `{reportsDestination}/{compilationName}/`
   */
  public val reportsDir: Provider<Directory>
}

/**
 * Directory containing machine-readable graph metadata JSON files.
 *
 * This directory contains one JSON file per dependency graph, with detailed information about
 * bindings, dependencies, scopes, and more. Each file follows the naming pattern
 * `graph-{fully.qualified.GraphName}.json`.
 *
 * The directory structure is: `{reportsDestination}/{compilationName}/graph-metadata/`
 *
 * This artifact is primarily intended for CI validation, automated analysis, and tooling that needs
 * programmatic access to Metro's dependency graph structure.
 */
public val MetroArtifacts.graphMetadataDir: Provider<Directory>
  get() = reportsDir.map { it.dir("graph-metadata") }
