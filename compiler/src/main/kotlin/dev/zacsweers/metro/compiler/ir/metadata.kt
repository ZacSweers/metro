// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.proto.BindsCallableId
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.proto.MultibindsCallableId
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.ClassId

private val BINDS_CALLABLE_ID_COMPARATOR: Comparator<BindsCallableId> =
  compareBy<BindsCallableId> { it.class_id }.thenBy { it.callable_name }.thenBy { it.is_property }

private val MULTIBINDS_CALLABLE_ID_COMPARATOR: Comparator<MultibindsCallableId> =
  compareBy<MultibindsCallableId> { it.class_id }.thenBy { it.callable_name }.thenBy { it.is_property }

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

context(context: IrMetroContext)
private fun <T> createBindLikeCallableId(
  declaration: IrSimpleFunction?,
  createType: (String, String, Boolean) -> T
): T? {
  // Grab the right declaration. If this is an override, look up the original
  val declarationToCheck =
    declaration
      ?.overriddenSymbolsSequence()
      ?.map { it.owner }
      ?.lastOrNull {
        it.isAnnotatedWithAny(context.symbols.classIds.bindsAnnotations)
      } ?: declaration

  return declarationToCheck?.propertyIfAccessor?.expectAsOrNull<IrDeclarationWithName>()?.let {
    when (it) {
      is IrSimpleFunction -> {
        val callableId = it.callableId
        return@let createType(
          callableId.classId!!.asString(),
          callableId.callableName.asString(),
          false,
        )
      }

      is IrProperty -> {
        val callableId = it.callableId
        return@let createType(
          callableId.classId!!.asString(),
          callableId.callableName.asString(),
          true,
        )
      }

      else -> null
    }
  }
}

context(context: IrMetroContext)
internal fun DependencyGraphNode.toProto(
  bindingGraph: IrBindingGraph,
  includedGraphClasses: Set<String>,
  parentGraphClasses: Set<String>,
  providerFields: List<String>,
  instanceFields: List<String>,
): DependencyGraphProto {
  val bindsCallableIds = mutableSetOf<BindsCallableId>()
  val multibindsCallableIds = mutableSetOf<MultibindsCallableId>()

  for (binding in bindingGraph.bindingsSnapshot().values) {
    when (binding) {
      is IrBinding.Alias -> {
        binding.ir?.let { declaration ->
          createBindLikeCallableId(declaration, ::BindsCallableId)?.let {
            bindsCallableIds.add(it)
          }
        }
      }
      is IrBinding.Multibinding -> {
        binding.declaration?.let { declaration ->
          createBindLikeCallableId(declaration, ::MultibindsCallableId)?.let {
            multibindsCallableIds.add(it)
          }
        }
      }
      else -> continue
    }
  }

  var multibindingAccessors = 0
  val accessorNames =
    accessors
      .sortedBy { it.first.ir.name.asString() }
      .onEachIndexed { index, (_, contextKey) ->
        val isMultibindingAccessor =
          bindingGraph.requireBinding(contextKey, IrBindingStack.empty()) is IrBinding.Multibinding
        if (isMultibindingAccessor) {
          multibindingAccessors = multibindingAccessors or (1 shl index)
        }
      }
      .map { it.first.ir.name.asString() }

  return createGraphProto(
    isGraph = true,
    providerFieldNames = providerFields,
    instanceFieldsNames = instanceFields,
    providerFactories = providerFactories,
    accessorNames = accessorNames,
    bindsCallableIds = bindsCallableIds,
    multibindsCallableIds = multibindsCallableIds,
    includedGraphClasses = includedGraphClasses,
    parentGraphClasses = parentGraphClasses,
    multibindingAccessorIndices = multibindingAccessors,
  )
}

internal fun BindingContainer.toProto(): DependencyGraphProto {
  return createGraphProto(
    isGraph = false,
    providerFactories = providerFactories.values.map { it.typeKey to it },
    bindsCallableIds =
      bindsCallables.mapToSet {
        BindsCallableId(
          it.callableId.classId!!.protoString,
          it.callableId.callableName.asString(),
          is_property = it.function.ir.propertyIfAccessor is IrProperty,
        )
      },
    includedBindingContainers = includes.map { it.asString() },
  )
}

// TODO metadata for graphs and containers are a bit conflated, would be nice to better separate
//  these
private fun createGraphProto(
  isGraph: Boolean,
  providerFieldNames: Collection<String> = emptyList(),
  instanceFieldsNames: Collection<String> = emptyList(),
  providerFactories: Collection<Pair<IrTypeKey, ProviderFactory>> = emptyList(),
  accessorNames: Collection<String> = emptyList(),
  // TODO would be nice if we could store this info entirely in metadata but requires types
  bindsCallableIds: Set<BindsCallableId> = emptySet(),
  multibindsCallableIds: Set<MultibindsCallableId> = emptySet(),
  includedGraphClasses: Collection<String> = emptyList(),
  parentGraphClasses: Collection<String> = emptyList(),
  multibindingAccessorIndices: Int = 0,
  includedBindingContainers: Collection<String> = emptyList(),
): DependencyGraphProto {
  return DependencyGraphProto(
    is_graph = isGraph,
    provider_field_names = providerFieldNames.sorted(),
    instance_field_names = instanceFieldsNames.sorted(),
    provider_factory_classes =
      providerFactories.map { (_, factory) -> factory.clazz.classIdOrFail.protoString }.sorted(),
    binds_callable_ids = bindsCallableIds.sortedWith(BINDS_CALLABLE_ID_COMPARATOR),
    multibinds_callable_ids = multibindsCallableIds.sortedWith(MULTIBINDS_CALLABLE_ID_COMPARATOR),
    accessor_callable_names = accessorNames.sorted(),
    included_classes = includedGraphClasses.sorted(),
    parent_graph_classes = parentGraphClasses.sorted(),
    multibinding_accessor_indices = multibindingAccessorIndices,
    included_binding_containers = includedBindingContainers.sorted(),
  )
}

private val ClassId.protoString: String
  get() = asString()
