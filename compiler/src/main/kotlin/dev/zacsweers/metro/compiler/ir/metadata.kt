// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.BitField
import dev.zacsweers.metro.compiler.METADATA_VERSION
import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.graph.GraphNode
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.proto.AssistedFactoryImplProto
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.InjectedClassProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.name.ClassId

/**
 * Factory function that ensures [METADATA_VERSION] is always set correctly.
 *
 * **Important:** When making breaking changes to [MetroMetadata] or related proto types in
 * `metro_metadata.proto`, you must increment `METADATA_VERSION` in `compiler/build.gradle.kts`.
 *
 * @see MetroMetadata
 * @see METADATA_VERSION
 */
internal fun createMetroMetadata(
  dependency_graph: DependencyGraphProto? = null,
  injected_class: InjectedClassProto? = null,
  assisted_factory_impl: AssistedFactoryImplProto? = null,
) =
  MetroMetadata(
    version = METADATA_VERSION,
    dependency_graph = dependency_graph,
    injected_class = injected_class,
    assisted_factory_impl = assisted_factory_impl,
  )

// TODO cache lookups of injected_class since it's checked multiple times
context(context: IrMetroContext)
internal var IrClass.metroMetadata: MetroMetadata?
  get() {
    return context.metadataDeclarationRegistrar.getCustomMetadataExtension(this, PLUGIN_ID)?.let {
      val metadata =
        try {
          MetroMetadata.ADAPTER.decode(it)
        } catch (e: Exception) {
          context.reportCompat(
            this,
            MetroDiagnostics.METRO_ERROR,
            "Failed to decode Metro metadata for '${classIdOrFail}'. " +
              "The metadata format may be incompatible with this Metro version. " +
              "Please recompile the upstream module with a compatible Metro version. " +
              "Error: ${e.message}",
          )
          return null
        }
      if (metadata.version != METADATA_VERSION) {
        context.reportCompat(
          this,
          MetroDiagnostics.METRO_ERROR,
          "Metro metadata version mismatch for '${classIdOrFail}'. " +
            "Metadata was generated with version ${metadata.version}, " +
            "but the current compiler expects version $METADATA_VERSION. " +
            "Please recompile the upstream module with a compatible Metro version.",
        )
      }
      metadata
    }
  }
  set(value) {
    if (value == null) return
    context.metadataDeclarationRegistrar.addCustomMetadataExtension(this, PLUGIN_ID, value.encode())
  }

internal fun GraphNode.toProto(bindingGraph: IrBindingGraph): DependencyGraphProto {
  var multibindingAccessors = BitField()
  val accessorNames =
    accessors
      .sortedBy { it.metroFunction.ir.name.asString() }
      .onEachIndexed { index, (contextKey, _, _) ->
        val isMultibindingAccessor =
          bindingGraph.requireBinding(contextKey) is IrBinding.Multibinding
        if (isMultibindingAccessor) {
          multibindingAccessors = multibindingAccessors.withSet(index)
        }
      }
      .map { it.metroFunction.ir.name.asString() }

  return createGraphProto(
    isGraph = true,
    providerFactories = providerFactories.values.flatten(),
    accessorNames = accessorNames,
    multibindingAccessorIndices = multibindingAccessors.toIntList(),
  )
}

internal fun BindingContainer.toProto(): DependencyGraphProto {
  return createGraphProto(
    isGraph = false,
    providerFactories = providerFactories.values,
    includedBindingContainers = includes.map { it.asString() },
  )
}

// TODO metadata for graphs and containers are a bit conflated, would be nice to better separate
//  these
private fun createGraphProto(
  isGraph: Boolean,
  providerFactories: Collection<ProviderFactory> = emptyList(),
  accessorNames: Collection<String> = emptyList(),
  multibindingAccessorIndices: List<Int> = emptyList(),
  includedBindingContainers: Collection<String> = emptyList(),
): DependencyGraphProto {
  return DependencyGraphProto(
    is_graph = isGraph,
    provider_factory_classes =
      providerFactories.map { factory -> factory.factoryClass.classIdOrFail.protoString }.sorted(),
    accessor_callable_names = accessorNames.sorted(),
    multibinding_accessor_indices = multibindingAccessorIndices,
    included_binding_containers = includedBindingContainers.sorted(),
  )
}

private val ClassId.protoString: String
  get() = asString()
