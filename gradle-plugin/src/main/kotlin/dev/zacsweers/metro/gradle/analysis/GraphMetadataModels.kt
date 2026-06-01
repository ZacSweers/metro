// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import dev.zacsweers.metro.gradle.artifacts.GenerateGraphMetadataTask
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
  /** Compiler options used for this graph's generated implementation. */
  val config: JsonObject? = null,
  /** Compiler-collected counters for this graph. */
  val stats: GraphStatsMetadata? = null,
  val bindings: List<BindingMetadata>,
)

/** Compiler-collected counters for a graph. */
@Serializable
public data class GraphStatsMetadata(
  val providerFactories: Int = 0,
  val bindsCallables: Int = 0,
  val multibindsCallables: Int = 0,
  val optionalBindings: Int = 0,
  val accessors: Int = 0,
  val injectors: Int = 0,
  val graphExtensionAccessors: Int = 0,
  val graphExtensionFactories: Int = 0,
  val includedGraphs: Int = 0,
  val bindingContainers: Int = 0,
  val dynamicBindings: Int = 0,
  val graphPrivateKeys: Int = 0,
  val publishedBindsKeys: Int = 0,
  val populatedKeys: Int = 0,
  val validatedKeys: Int = 0,
  val reachableKeys: Int = 0,
  val deferredKeys: Int = 0,
  val unusedInputs: Int = 0,
  val providerProperties: Int = 0,
  val scopedProviderProperties: Int = 0,
  val shards: Int = 0,
  val optimizations: GraphOptimizationStatsMetadata = GraphOptimizationStatsMetadata(),
)

/** Compiler-collected counters for graph/codegen optimizations. */
@Serializable
public data class GraphOptimizationStatsMetadata(
  val bindingsPrunedByShrinking: Int = 0,
  val classConstructorDirectInvocations: Int = 0,
  val classConstructorNewInstanceCalls: Int = 0,
  val providerDirectInvocations: Int = 0,
  val providerNewInstanceCalls: Int = 0,
  val shardsGenerated: Int = 0,
  val shardedSupertypes: Int = 0,
  val shardedInitFunctions: Int = 0,
  val providerInlines: Int = 0,
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
  /**
   * For Assisted factory bindings, contains metadata about the encapsulated target binding.
   * Assisted-inject targets are not in the main graph; their info is exposed here.
   */
  val assistedTarget: AssistedTargetMetadata? = null,
)

/**
 * Metadata for an assisted-inject target binding encapsulated within an Assisted factory. Since
 * assisted-inject targets are not in the main binding graph, their information is exposed through
 * this nested structure on the factory binding.
 *
 * This has the same structure as [BindingMetadata] plus [assistedParameters].
 */
@Serializable
public data class AssistedTargetMetadata(
  val key: String,
  val bindingKind: String,
  val scope: String? = null,
  val isScoped: Boolean = false,
  val nameHint: String,
  /** The target's actual dependencies (not Provider-wrapped). */
  val dependencies: List<DependencyMetadata>,
  val origin: String? = null,
  val declaration: String? = null,
  val multibinding: MultibindingMetadata? = null,
  val optionalWrapper: OptionalWrapperMetadata? = null,
  val isSynthetic: Boolean = false,
  /** Parameters injected at call time (not from the graph). */
  val assistedParameters: List<AssistedParameterMetadata> = emptyList(),
)

/** Metadata for an assisted parameter (injected at call time). */
@Serializable public data class AssistedParameterMetadata(val key: String, val name: String)

/** Metadata for a dependency reference. */
@Serializable
public data class DependencyMetadata(
  val key: String,
  val hasDefault: Boolean,
  /** Wrapper type if wrapped (e.g., "Provider", "Lazy"). Null if not wrapped. */
  val wrapperType: String? = null,
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
