// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.ir.graph.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.name.ClassId

// TODO cache lookups of injected_class since it's checked multiple times
context(context: IrMetroContext)
internal var IrClass.metroMetadata: MetroMetadata?
  get() {
    return context.metadataDeclarationRegistrar.getCustomMetadataExtension(this, PLUGIN_ID)?.let {
      MetroMetadata.ADAPTER.decode(it)
    }
  }
  set(value) {
    if (value == null) return
    context.metadataDeclarationRegistrar.addCustomMetadataExtension(this, PLUGIN_ID, value.encode())
  }

internal fun DependencyGraphNode.toProto(bindingGraph: IrBindingGraph): DependencyGraphProto {
  var multibindingAccessors = 0
  val accessorNames =
    accessors
      .sortedBy { it.metroFunction.ir.name.asString() }
      .onEachIndexed { index, (contextKey, _, _) ->
        val isMultibindingAccessor =
          bindingGraph.requireBinding(contextKey) is IrBinding.Multibinding
        if (isMultibindingAccessor) {
          multibindingAccessors = multibindingAccessors or (1 shl index)
        }
      }
      .map { it.metroFunction.ir.name.asString() }

  return createGraphProto(
    isGraph = true,
    providerFactories = providerFactories,
    accessorNames = accessorNames,
    multibindingAccessorIndices = multibindingAccessors,
  )
}

internal fun BindingContainer.toProto(): DependencyGraphProto {
  return createGraphProto(
    isGraph = false,
    providerFactories = providerFactories.values.map { it.typeKey to it },
    includedBindingContainers = includes.map { it.asString() },
  )
}

// TODO metadata for graphs and containers are a bit conflated, would be nice to better separate
//  these
private fun createGraphProto(
  isGraph: Boolean,
  providerFactories: Collection<Pair<IrTypeKey, ProviderFactory>> = emptyList(),
  accessorNames: Collection<String> = emptyList(),
  multibindingAccessorIndices: Int = 0,
  includedBindingContainers: Collection<String> = emptyList(),
): DependencyGraphProto {
  return DependencyGraphProto(
    is_graph = isGraph,
    provider_factory_classes =
      providerFactories
        .map { (_, factory) -> factory.factoryClass.classIdOrFail.protoString }
        .sorted(),
    accessor_callable_names = accessorNames.sorted(),
    multibinding_accessor_indices = multibindingAccessorIndices,
    included_binding_containers = includedBindingContainers.sorted(),
  )
}

private val ClassId.protoString: String
  get() = asString()
