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

// Represents an object graph's structure and relationships
internal data class DependencyGraphNode(
  val sourceGraph: IrClass,
  val isExtendable: Boolean,
  val dependencies: Map<TypeKey, DependencyGraphNode>,
  val scopes: Set<IrAnnotation>,
  val providerFactories: List<Pair<TypeKey, ProviderFactory>>,
  // Types accessible via this graph (includes inherited)
  // Dagger calls these "provision methods", but that's a bit vague IMO
  val accessors: List<Pair<MetroSimpleFunction, ContextualTypeKey>>,
  val bindsFunctions: List<Pair<MetroSimpleFunction, ContextualTypeKey>>,
  val injectors: List<Pair<MetroSimpleFunction, ContextualTypeKey>>,
  val isExternal: Boolean,
  val creator: Creator?,
  val typeKey: TypeKey,
  val proto: DependencyGraphProto? = null,
) {

  val multibindingAccessors by unsafeLazy {
    proto
      ?.let {
        val bitfield = it.multibinding_accessor_indices
        val multibindingCallableIds =
          it.accessor_callable_ids.filterIndexedTo(mutableSetOf()) { index, _ ->
            (bitfield shr index) and 1 == 1
          }
        accessors
          .filter { it.first.ir.name.asString() in multibindingCallableIds }
          .mapToSet { it.first }
      }
      .orEmpty()
  }

  override fun toString(): String = typeKey.render(short = true)

  data class Creator(
    val type: IrClass,
    val createFunction: IrSimpleFunction,
    val parameters: Parameters<ConstructorParameter>,
  )

  // Lazy-wrapped to cache these per-node
  // TODO make this smarter and check for already visited graphs while searching
  val allDependencies by lazy {
    sequence {
        yieldAll(dependencies.values)
        dependencies.values.forEach { node -> yieldAll(node.dependencies.values) }
      }
      .toSet()
  }
}
