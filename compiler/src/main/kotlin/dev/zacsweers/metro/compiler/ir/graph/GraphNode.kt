// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.BitField
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.BindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrBindingContainerCallable
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.MultibindsCallable
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.metroGraphOrNull
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.name.ClassId

/** Represents a dependency graph's structure and relationships. */
internal sealed class GraphNode {
  abstract val sourceGraph: IrClass
  abstract val supertypes: List<IrType>
  abstract val includedGraphNodes: Map<IrTypeKey, GraphNode>
  abstract val scopes: Set<IrAnnotation>
  abstract val aggregationScopes: Set<ClassId>
  abstract val providerFactories: Map<IrTypeKey, List<ProviderFactory>>
  /**
   * Types accessible via this graph (includes inherited). Dagger calls these "provision methods".
   */
  abstract val accessors: List<GraphAccessor>
  abstract val bindsCallables: Map<IrTypeKey, List<BindsCallable>>
  abstract val multibindsCallables: Set<MultibindsCallable>
  abstract val optionalKeys: Map<IrTypeKey, Set<BindsOptionalOfCallable>>
  abstract val parentGraph: GraphNode?
  abstract val typeKey: IrTypeKey

  val publicAccessors: Set<IrTypeKey> by memoize { accessors.mapToSet { it.contextKey.typeKey } }

  val contextKey: IrContextualTypeKey = IrContextualTypeKey(typeKey)

  // For quick lookups
  val supertypeClassIds: Set<ClassId> by memoize {
    supertypes.mapNotNullToSet { it.classOrNull?.owner?.classId }
  }

  val metroGraph: IrClass? by memoize { sourceGraph.metroGraphOrNull }

  val metroGraphOrFail: IrClass by memoize {
    metroGraph ?: reportCompilerBug("No generated MetroGraph found: ${sourceGraph.kotlinFqName}")
  }

  /** [IrTypeKey] of the contributed graph extension, if any. */
  val contributedGraphTypeKey: IrTypeKey? by memoize {
    if (sourceGraph.origin == Origins.GeneratedGraphExtension) {
      IrTypeKey(sourceGraph.superTypes.first())
    } else {
      null
    }
  }

  /** [contributedGraphTypeKey] if not null, otherwise [GraphNode.typeKey]. */
  val originalTypeKey: IrTypeKey by memoize { contributedGraphTypeKey ?: typeKey }

  val reportableSourceGraphDeclaration: IrElement by memoize {
    if (metroGraph?.origin == Origins.GeneratedDynamicGraph) {
      val source = metroGraph?.generatedDynamicGraphData?.sourceExpression
      if (source != null) {
        return@memoize source
      }
    }
    return@memoize generateSequence(sourceGraph) { it.parentClassOrNull }
      .firstOrNull {
        // Skip impl graphs
        it.sourceGraphIfMetroGraph == it && it.fileOrNull != null
      }
      ?: reportCompilerBug(
        "Could not find a reportable source graph declaration for ${sourceGraph.kotlinFqName}"
      )
  }

  val allIncludedNodes: Set<GraphNode> by memoize {
    buildMap(::recurseIncludedNodes).values.toSet()
  }

  val allParentGraphs: Map<IrTypeKey, GraphNode> by memoize { buildMap(::recurseParents) }

  private fun recurseIncludedNodes(builder: MutableMap<IrTypeKey, GraphNode>) {
    for ((key, includedNode) in includedGraphNodes) {
      if (key !in builder) {
        builder[key] = includedNode
        includedNode.recurseIncludedNodes(builder)
      }
    }
    // Propagate included nodes from parent graph
    parentGraph?.let { parent ->
      for (includedFromParent in parent.allIncludedNodes) {
        builder[includedFromParent.typeKey] = includedFromParent
      }
    }
  }

  private fun recurseParents(builder: MutableMap<IrTypeKey, GraphNode>) {
    parentGraph?.let { parent ->
      builder[parent.typeKey] = parent
      parent.recurseParents(builder)
    }
  }

  final override fun toString(): String = typeKey.render(short = true)

  /** A graph node for a precompiled/external dependency graph. */
  data class External(
    override val sourceGraph: IrClass,
    override val supertypes: List<IrType>,
    override val includedGraphNodes: Map<IrTypeKey, GraphNode>,
    override val scopes: Set<IrAnnotation>,
    override val aggregationScopes: Set<ClassId>,
    override val providerFactories: Map<IrTypeKey, List<ProviderFactory>>,
    override val accessors: List<GraphAccessor>,
    override val bindsCallables: Map<IrTypeKey, List<BindsCallable>>,
    override val multibindsCallables: Set<MultibindsCallable>,
    override val optionalKeys: Map<IrTypeKey, Set<BindsOptionalOfCallable>>,
    override val parentGraph: GraphNode?,
    override val typeKey: IrTypeKey = IrTypeKey(sourceGraph.typeWith()),
  ) : GraphNode()

  /** A graph node for a graph being compiled in the current compilation unit. */
  data class Local(
    override val sourceGraph: IrClass,
    override val supertypes: List<IrType>,
    override val includedGraphNodes: Map<IrTypeKey, GraphNode>,
    val graphExtensions: Map<IrTypeKey, List<GraphExtensionAccessor>>,
    override val scopes: Set<IrAnnotation>,
    override val aggregationScopes: Set<ClassId>,
    override val providerFactories: Map<IrTypeKey, List<ProviderFactory>>,
    override val accessors: List<GraphAccessor>,
    override val bindsCallables: Map<IrTypeKey, List<BindsCallable>>,
    override val multibindsCallables: Set<MultibindsCallable>,
    override val optionalKeys: Map<IrTypeKey, Set<BindsOptionalOfCallable>>,
    /** Binding containers that need a managed instance. */
    val bindingContainers: Set<IrClass>,
    val annotationDeclaredBindingContainers: Map<IrTypeKey, IrElement>,
    /**
     * Set of all dynamic callables for each type key (allows tracking multiple dynamic bindings).
     */
    val dynamicTypeKeys: Map<IrTypeKey, Set<IrBindingContainerCallable>>,
    /** Fake overrides of binds functions that need stubbing. */
    val bindsFunctions: List<MetroSimpleFunction>,
    /** TypeKey key is the injected type wrapped in MembersInjector. */
    val injectors: List<InjectorFunction>,
    val creator: Creator?,
    override val parentGraph: GraphNode?,
    override val typeKey: IrTypeKey = IrTypeKey(sourceGraph.typeWith()),
    var proto: DependencyGraphProto? = null,
  ) : GraphNode() {
    val hasExtensions = graphExtensions.isNotEmpty()
  }

  sealed class Creator {
    abstract val type: IrClass
    abstract val function: IrFunction
    abstract val parameters: Parameters
    abstract val bindingContainersParameterIndices: BitField

    val parametersByTypeKey by memoize { parameters.regularParameters.associateBy { it.typeKey } }

    val typeKey by memoize { IrTypeKey(type.typeWith()) }

    data class Constructor(
      override val type: IrClass,
      override val function: IrConstructor,
      override val parameters: Parameters,
      override val bindingContainersParameterIndices: BitField,
    ) : Creator()

    data class Factory(
      override val type: IrClass,
      override val function: IrSimpleFunction,
      override val parameters: Parameters,
      override val bindingContainersParameterIndices: BitField,
    ) : Creator()
  }
}

internal data class GraphExtensionAccessor(
  val accessor: MetroSimpleFunction,
  val key: IrContextualTypeKey,
  val isFactory: Boolean,
  val isFactorySAM: Boolean,
) {
  val isSimpleAccessor: Boolean
    get() = !isFactorySAM && !isFactory
}
