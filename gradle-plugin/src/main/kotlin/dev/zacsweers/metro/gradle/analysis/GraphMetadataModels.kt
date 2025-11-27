// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import dev.zacsweers.metro.gradle.artifacts.GenerateGraphMetadataTask
import kotlinx.serialization.Serializable

/** Aggregated graph metadata for a project, as produced by [GenerateGraphMetadataTask]. */
@Serializable
public data class AggregatedGraphMetadata(
  val projectPath: String,
  val graphCount: Int,
  val graphs: List<GraphMetadata>,
)

/** Metadata for a single dependency graph. */
@Serializable
public data class GraphMetadata(
  val graph: String,
  val scopes: List<String>,
  val aggregationScopes: List<String>,
  val bindings: List<BindingMetadata>,
)

/** Metadata for a single binding within a graph. */
@Serializable
public data class BindingMetadata(
  val key: String,
  val bindingKind: String,
  val scope: String? = null,
  val isScoped: Boolean,
  val nameHint: String,
  val dependencies: List<DependencyMetadata>,
  val origin: String? = null,
  val declaration: String? = null,
  val multibinding: MultibindingMetadata? = null,
  val optionalWrapper: OptionalWrapperMetadata? = null,
  val aliasTarget: String? = null,
  /** True if this is a generated/synthetic binding (e.g., alias, contribution). */
  val isSynthetic: Boolean = false,
)

/** Metadata for a dependency reference. */
@Serializable
public data class DependencyMetadata(
  val key: String,
  val hasDefault: Boolean,
  /** True if wrapped in Provider/Lazy (breaks cycles). */
  val isDeferrable: Boolean = false,
  /** True if this is an assisted parameter. */
  val isAssisted: Boolean = false,
  /** True if this is an accessor (graph entry point). */
  val isAccessor: Boolean = false,
)

/** Metadata for multibinding configuration. */
@Serializable
public data class MultibindingMetadata(
  val type: String, // "MAP" or "SET"
  val allowEmpty: Boolean,
  val sources: List<String>,
)

/** Metadata for optional wrapper bindings. */
@Serializable
public data class OptionalWrapperMetadata(
  val wrappedType: String,
  val allowsAbsent: Boolean,
  val wrapperKey: String,
)
