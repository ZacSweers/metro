// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.transformers.ProviderFactory
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.typeWith

// Represents an object graph's structure and relationships
internal data class DependencyGraphNode(
  val sourceGraph: IrClass,
  val isExtendable: Boolean,
  val supertypeNodes: Map<TypeKey, DependencyGraphNode>,
  val scopes: Set<IrAnnotation>,
  val providerFactories: List<Pair<TypeKey, ProviderFactory>>,
  // Types accessible via this graph (includes inherited)
  // Dagger calls these "provision methods", but that's a bit vague IMO
  val accessors: List<Pair<MetroSimpleFunction, ContextualTypeKey>>,
  val bindsFunctions: List<Pair<MetroSimpleFunction, ContextualTypeKey>>,
  // TypeKey key is the injected type wrapped in MembersInjector
  val injectors: List<Pair<MetroSimpleFunction, TypeKey>>,
  val isExternal: Boolean,
  val creator: Creator?,
  val parentGraphNodes: Map<TypeKey, DependencyGraphNode>,
  val typeKey: TypeKey = TypeKey(sourceGraph.typeWith()),
  val proto: DependencyGraphProto? = null,
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

  val allSupertypeNodes by lazy {
    buildMap { recurseSupertypeNodes(this) }
      .values
      .toSet()
  }

  val allParents by lazy {
    buildMap {
      recurseParents(this)
    }
      .keys
  }

  override fun toString(): String = typeKey.render(short = true)

  data class Creator(
    val type: IrClass,
    val createFunction: IrSimpleFunction,
    val parameters: Parameters<ConstructorParameter>,
  )
}

private fun DependencyGraphNode.recurseSupertypeNodes(
  builder: MutableMap<TypeKey, DependencyGraphNode>,
) {
  for ((key, node) in supertypeNodes) {
    if (key !in builder) {
      builder.put(key, node)
      node.recurseSupertypeNodes(builder)
    }
  }
}

private fun DependencyGraphNode.recurseParents(builder: MutableMap<TypeKey, DependencyGraphNode>) {
  for ((key, value) in parentGraphNodes) {
    builder.put(key, value)
    value.recurseParents(builder)
  }
}
