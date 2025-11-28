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
  /** Root entry points into the graph (accessors and injectors). */
  val roots: RootsMetadata? = null,
  /** Graph extension information. */
  val extensions: ExtensionsMetadata? = null,
  val bindings: List<BindingMetadata>,
)

/** Root entry points into the graph. */
@Serializable
public data class RootsMetadata(
  /** Accessor properties that expose bindings from the graph. */
  val accessors: List<AccessorMetadata> = emptyList(),
  /** Injector functions that inject dependencies into targets. */
  val injectors: List<InjectorMetadata> = emptyList(),
)

/** Metadata for an accessor property. */
@Serializable public data class AccessorMetadata(val key: String, val isDeferrable: Boolean = false)

/** Metadata for an injector function. */
@Serializable public data class InjectorMetadata(val key: String)

/** Graph extension information. */
@Serializable
public data class ExtensionsMetadata(
  /** Extension accessors (non-factory). */
  val accessors: List<ExtensionAccessorMetadata> = emptyList(),
  /** Extension factory accessors. */
  val factoryAccessors: List<ExtensionFactoryAccessorMetadata> = emptyList(),
  /** Factory interfaces implemented by this graph. */
  val factoriesImplemented: List<String> = emptyList(),
)

/** Metadata for an extension accessor. */
@Serializable public data class ExtensionAccessorMetadata(val key: String)

/** Metadata for an extension factory accessor. */
@Serializable
public data class ExtensionFactoryAccessorMetadata(val key: String, val isSAM: Boolean = false)

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
  /** Wrapper type if wrapped (e.g., "Provider", "Lazy"). Null if not wrapped. */
  val wrapperType: String? = null,
  /** True if this is an assisted parameter. */
  val isAssisted: Boolean = false,
) {
  /** True if wrapped in Provider/Lazy (breaks cycles). */
  val isDeferrable: Boolean
    get() = wrapperType != null
}

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
