// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith

// Represents an object graph's structure and relationships
internal data class DependencyGraphNode(
  val sourceGraph: IrClass,
  val supertypes: List<IrType>,
  val isExtendable: Boolean,
  val includedGraphNodes: Map<IrTypeKey, DependencyGraphNode>,
  val contributedGraphs: Map<IrTypeKey, MetroSimpleFunction>,
  val scopes: Set<IrAnnotation>,
  val providerFactories: List<Pair<IrTypeKey, ProviderFactory>>,
  // Types accessible via this graph (includes inherited)
  // Dagger calls these "provision methods", but that's a bit vague IMO
  val accessors: List<Pair<MetroSimpleFunction, IrContextualTypeKey>>,
  val bindsFunctions: List<Pair<MetroSimpleFunction, IrContextualTypeKey>>,
  // TypeKey key is the injected type wrapped in MembersInjector
  val injectors: List<Pair<MetroSimpleFunction, IrTypeKey>>,
  val isExternal: Boolean,
  val creator: Creator?,
  val extendedGraphNodes: Map<IrTypeKey, DependencyGraphNode>,
  val typeKey: IrTypeKey = IrTypeKey(sourceGraph.typeWith()),
  // TODO not ideal that this is mutable/lateinit but welp
  //  maybe we track these protos separately somewhere?
  var proto: DependencyGraphProto? = null,
) {

  val publicAccessors by unsafeLazy { accessors.mapToSet { (_, contextKey) -> contextKey.typeKey } }

  val multibindingAccessors by unsafeLazy {
    proto
      ?.let {
        val bitfield = it.multibinding_accessor_indices
        val multibindingCallableIds =
          it.accessor_callable_names.filterIndexedTo(mutableSetOf()) { index, _ ->
            (bitfield shr index) and 1 == 1
          }
        accessors
          .filter { it.first.ir.name.asString() in multibindingCallableIds }
          .mapToSet { it.first }
      }
      .orEmpty()
  }

  val allIncludedNodes by lazy { buildMap { recurseIncludedNodes(this) }.values.toSet() }

  val allExtendedNodes by lazy { buildMap { recurseParents(this) } }

  override fun toString(): String = typeKey.render(short = true)

  sealed interface Creator {
    val function: IrFunction
    val parameters: Parameters

    data class Constructor(
      override val function: IrConstructor,
      override val parameters: Parameters,
    ) : Creator

    data class Factory(
      val type: IrClass,
      override val function: IrSimpleFunction,
      override val parameters: Parameters,
    ) : Creator
  }
}

private fun DependencyGraphNode.recurseIncludedNodes(
  builder: MutableMap<IrTypeKey, DependencyGraphNode>
) {
  for ((key, node) in includedGraphNodes) {
    if (key !in builder) {
      builder.put(key, node)
      node.recurseIncludedNodes(builder)
    }
  }
  // Propagate included nodes from parent graphs
  for (node in extendedGraphNodes.values) {
    for (includedFromParent in node.allIncludedNodes) {
      builder[includedFromParent.typeKey] = includedFromParent
    }
  }
}

private fun DependencyGraphNode.recurseParents(
  builder: MutableMap<IrTypeKey, DependencyGraphNode>
) {
  for ((key, value) in extendedGraphNodes) {
    builder.put(key, value)
    value.recurseParents(builder)
  }
}
