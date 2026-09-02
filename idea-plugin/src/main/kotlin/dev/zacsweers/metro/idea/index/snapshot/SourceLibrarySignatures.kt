// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.vfs.VirtualFile
import dev.zacsweers.metro.idea.index.BindingData
import dev.zacsweers.metro.idea.index.FactoryInputEntry
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.GraphInterfaceSurface
import dev.zacsweers.metro.idea.index.writtenFactoryBudgetKey
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.DynamicGraphId
import dev.zacsweers.metro.idea.model.GraphCallableReference
import dev.zacsweers.metro.idea.model.GraphCallableSignature
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.GraphDefaultImplementation
import dev.zacsweers.metro.idea.model.GraphExtensionFactoryAccessor
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import org.jetbrains.kotlin.name.ClassId

/**
 * Compares the source fields that affect library lookup. Runs in the snapshot read action because
 * signatures include pointer availability. The full shard signature stays private to this file.
 */
internal fun sourceLibraryInputsChanged(before: FileShard?, after: FileShard?): Boolean {
  return before?.librarySignature() != after?.librarySignature()
}

/** Only values that change classpath lookup or the actual factory use site participate here. */
private fun FileShard.librarySignature(): SourceLibraryShardSignature {
  return SourceLibraryShardSignature(
    graphs.map { graph ->
      GraphLibrarySignature(
        graph.declarationId,
        graph.scopeKeys,
        graph.scopingAnnotations,
        graph.excludes,
        graph.bindingContainers,
        graph.includedBindingContainers,
        graph.includedDependencies,
        graph.isExtension,
        graph.selfReferences,
        graph.supertypeKeys,
        graph.supertypeDeclarations,
        graph.extensionCreations,
        graph.extensionFactories.map(::extensionFactoryLibrarySignature),
        graph.defaultImplementations.map(::defaultImplementationLibrarySignature),
        graph.injectedMemberOwnerIds,
        graph.daggerAnvilInteropEnabled,
        graph.pointer.element != null,
      )
    },
    contributions.map(::contributionLibrarySignature),
    consumers.map(::consumerLibrarySignature),
    bindings.mapNotNull { it.writtenFactoryBudgetKey() },
    bindings.mapNotNull(::bindingLibrarySignature),
    factoryInputs.map { input ->
      FactoryInputLibrarySignature(
        input.id,
        input.consumers.map(::consumerLibrarySignature),
        input.bindings.mapNotNull { it.writtenFactoryBudgetKey() },
        input.bindings.mapNotNull(::bindingLibrarySignature),
      )
    },
    dynamicGraphs.map { dynamicGraph ->
      DynamicGraphLibrarySignature(
        dynamicGraph.id,
        dynamicGraph.targetGraph,
        dynamicGraph.bindingKeys,
        dynamicGraph.isFactory,
        dynamicGraph.pointer.element != null,
      )
    },
    graphInterfaces.map(::graphInterfaceLibrarySignature),
  )
}

private fun contributionLibrarySignature(
  contribution: ContributionEntry
): ContributionLibrarySignature {
  return ContributionLibrarySignature(
    contribution.scopeKeys,
    contribution.classId,
    contribution.kind,
    contribution.replaces,
    contribution.graphExtension,
    contribution.pointer.virtualFile,
    contribution.pointer.element != null,
  )
}

private fun consumerLibrarySignature(consumer: ConsumerEntry): ConsumerLibrarySignature {
  return ConsumerLibrarySignature(
    contextKeyLibrarySignature(consumer.contextKey),
    consumer.typeClassId,
    consumer.multibindingId,
    consumer.graphId,
    consumer.includedContainerKey,
    consumer.pointer.virtualFile,
    consumer.pointer.element != null,
    consumer.originClassId,
    consumer.containerId,
    consumer.contributionScopes,
    consumer.graphContribution,
    consumer.memberOwnerClassId,
    consumer.graphRequestKind,
    consumer.isSuspend,
    consumer.isOptional,
  )
}

private fun extensionFactoryLibrarySignature(
  factory: GraphExtensionFactoryAccessor
): ExtensionFactoryLibrarySignature {
  return ExtensionFactoryLibrarySignature(
    factory.factoryKey,
    factory.extensionKey,
    factory.extension,
    factory.pointer.virtualFile,
    factory.pointer.element != null,
  )
}

private fun callableLibrarySignature(
  callable: GraphCallableReference
): GraphCallableLibrarySignature {
  return GraphCallableLibrarySignature(
    callable.signature,
    callable.pointer.virtualFile,
    callable.pointer.element != null,
  )
}

private fun defaultImplementationLibrarySignature(
  implementation: GraphDefaultImplementation
): GraphDefaultImplementationLibrarySignature {
  return GraphDefaultImplementationLibrarySignature(
    callableLibrarySignature(implementation.declaration),
    implementation.overriddenDeclarations.map(::callableLibrarySignature),
    implementation.isOptional,
  )
}

private fun graphInterfaceLibrarySignature(
  surface: GraphInterfaceSurface
): GraphInterfaceLibrarySignature {
  return GraphInterfaceLibrarySignature(
    contributionLibrarySignature(surface.contribution),
    surface.supertypeKeys,
    surface.supertypeDeclarations,
    surface.bindings.map { binding ->
      val data = binding.data
      GraphInterfaceBindingLibrarySignature(
        data.key,
        data.kind,
        data.scope,
        data.implementationName,
        data.consumedKey?.let(::contextKeyLibrarySignature),
        data.multibindingId,
        data.originClassId,
        data.replaces,
        data.contributionScopes,
        data.priority,
        data.priorityFromAnvilRank,
        data.dependencies.map(::contextKeyLibrarySignature),
        data.constructorDependencies.map(::contextKeyLibrarySignature),
        data.memberDependencies.map(::contextKeyLibrarySignature),
        data.memberInjectionOwnerIds,
        data.isSuspend,
        data.isAssisted,
        data.mapKeyValue,
        data.isClassContribution,
        data.allowEmpty,
        data.isGraphPrivate,
        binding.pointer.virtualFile,
        binding.pointer.element != null,
      )
    },
    surface.consumers.map(::consumerLibrarySignature),
    surface.extensionCreations,
    surface.extensionFactories.map(::extensionFactoryLibrarySignature),
    surface.defaultImplementations.map(::defaultImplementationLibrarySignature),
    surface.injectedMemberOwnerIds,
  )
}

private fun bindingLibrarySignature(binding: KaBinding): BindingLibrarySignature? {
  val isAssistedFactory = binding is KaBinding.AssistedFactory
  val isGeneratedContribution =
    binding is KaBinding.Provided && binding.isClassContribution ||
      binding is KaBinding.Alias && binding.isClassContribution
  val graphInput = binding as? KaBinding.BoundInstance
  val isFactoryInput =
    graphInput != null && (graphInput.isGraphInput || graphInput.isBindingContainerInput)
  if (!isAssistedFactory && !isGeneratedContribution && !isFactoryInput) return null
  val hasPriorityMetadata = binding.priority != Int.MIN_VALUE || binding.priorityFromAnvilRank
  val needsLibrarySignature =
    isFactoryInput || isAssistedFactory || binding.dependencies.isNotEmpty() || hasPriorityMetadata
  if (!needsLibrarySignature) return null
  return BindingLibrarySignature(
    binding.typeKey,
    binding.originClassId,
    binding.pointer.virtualFile,
    binding.pointer.element != null,
    isAssistedFactory,
    binding.scope,
    binding.contributionScopes,
    binding.priority,
    binding.priorityFromAnvilRank,
    binding.dependencies,
    binding.ownerGraphId,
    graphInput?.additionalOwnerGraphIds.orEmpty(),
    graphInput?.isGraphInput == true,
    graphInput?.isBindingContainerInput == true,
    (binding as? KaBinding.AssistedFactory)?.let(::assistedFactoryDefinitionSignature),
  )
}

/** Defaults and raw wrappers are metadata here, although contextual-key equality omits them. */
internal fun assistedFactoryDefinitionSignature(
  binding: KaBinding.AssistedFactory
): AssistedFactoryDefinitionSignature {
  return AssistedFactoryDefinitionSignature(
    binding.typeKey,
    binding.originClassId,
    binding.pointer.virtualFile,
    binding.scope,
    binding.targetTypeKey,
    (binding.targetConstructorDependencies + binding.targetMemberDependencies).map(
      ::contextKeyLibrarySignature
    ),
    binding.targetConstructorDependencies.size,
    binding.memberInjectionOwnerIds,
    binding.factoryFunctionName,
    binding.factoryFunctionIsSuspend,
  )
}

private data class SourceLibraryShardSignature(
  val graphs: List<GraphLibrarySignature>,
  val contributions: List<ContributionLibrarySignature>,
  val consumers: List<ConsumerLibrarySignature>,
  val writtenBindingKeys: List<KaTypeKey>,
  val bindings: List<BindingLibrarySignature>,
  val factoryInputs: List<FactoryInputLibrarySignature>,
  val dynamicGraphs: List<DynamicGraphLibrarySignature>,
  val graphInterfaces: List<GraphInterfaceLibrarySignature>,
)

private data class DynamicGraphLibrarySignature(
  val id: DynamicGraphId,
  val targetGraph: GraphReference,
  val bindingKeys: Set<KaTypeKey>,
  val isFactory: Boolean,
  val pointerIsValid: Boolean,
)

private data class GraphLibrarySignature(
  val declarationId: GraphDeclarationId,
  val scopes: Set<ClassId>,
  val scopingAnnotations: Set<KaAnnotationSnapshot>,
  val excludes: Set<ClassId>,
  val bindingContainers: Set<ClassId>,
  val includedContainers: Set<KaTypeKey>,
  val includedDependencies: Set<KaTypeKey>,
  val isExtension: Boolean,
  val selfReferences: Set<GraphReference>,
  val supertypeKeys: Set<KaTypeKey>,
  val supertypeDeclarations: Set<GraphReference>,
  val extensionCreations: Set<GraphReference>,
  val extensionFactories: List<ExtensionFactoryLibrarySignature>,
  val defaultImplementations: List<GraphDefaultImplementationLibrarySignature>,
  val injectedMemberOwnerIds: Set<ClassId>,
  val daggerAnvilInteropEnabled: Boolean,
  val pointerIsValid: Boolean,
)

private data class ContributionLibrarySignature(
  val scopes: Set<ClassId>,
  val classId: ClassId?,
  val kind: ContributionEntry.Kind,
  val replaces: Set<ClassId>,
  val graphExtension: GraphReference?,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
)

private data class ConsumerLibrarySignature(
  val key: ContextKeyLibrarySignature,
  val classId: ClassId?,
  val multibindingId: String?,
  val graphId: GraphDeclarationId?,
  val includedContainerKey: KaTypeKey?,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
  val originClassId: ClassId?,
  val containerId: ClassId?,
  val contributionScopes: Set<ClassId>,
  val graphContribution: GraphReference?,
  val memberOwnerClassId: ClassId?,
  val graphRequestKind: ConsumerEntry.GraphRequestKind?,
  val isSuspend: Boolean,
  val isOptional: Boolean,
)

private data class ExtensionFactoryLibrarySignature(
  val factoryKey: KaTypeKey,
  val extensionKey: KaTypeKey,
  val extension: GraphReference,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
)

private data class GraphCallableLibrarySignature(
  val signature: GraphCallableSignature,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
)

private data class GraphDefaultImplementationLibrarySignature(
  val declaration: GraphCallableLibrarySignature,
  val overriddenDeclarations: List<GraphCallableLibrarySignature>,
  val isOptional: Boolean,
)

private data class GraphInterfaceLibrarySignature(
  val contribution: ContributionLibrarySignature,
  val supertypeKeys: Set<KaTypeKey>,
  val supertypeDeclarations: Set<GraphReference>,
  val bindings: List<GraphInterfaceBindingLibrarySignature>,
  val consumers: List<ConsumerLibrarySignature>,
  val extensionCreations: Set<GraphReference>,
  val extensionFactories: List<ExtensionFactoryLibrarySignature>,
  val defaultImplementations: List<GraphDefaultImplementationLibrarySignature>,
  val injectedMemberOwnerIds: Set<ClassId>,
)

private data class GraphInterfaceBindingLibrarySignature(
  val key: KaTypeKey,
  val kind: BindingData.Kind,
  val scope: KaAnnotationSnapshot?,
  val implementationName: String?,
  val consumedKey: ContextKeyLibrarySignature?,
  val multibindingId: String?,
  val originClassId: ClassId?,
  val replaces: Set<ClassId>,
  val contributionScopes: Set<ClassId>,
  val priority: Int,
  val priorityFromAnvilRank: Boolean,
  val dependencies: List<ContextKeyLibrarySignature>,
  val constructorDependencies: List<ContextKeyLibrarySignature>,
  val memberDependencies: List<ContextKeyLibrarySignature>,
  val memberOwnerIds: Set<ClassId>,
  val isSuspend: Boolean,
  val isAssisted: Boolean,
  val mapKeyValue: String?,
  val isClassContribution: Boolean,
  val allowEmpty: Boolean,
  val isGraphPrivate: Boolean,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
)

private data class BindingLibrarySignature(
  val key: KaTypeKey,
  val originClassId: ClassId?,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
  val isAssistedFactory: Boolean,
  val scope: KaAnnotationSnapshot?,
  val contributionScopes: Set<ClassId>,
  val priority: Int,
  val priorityFromAnvilRank: Boolean,
  val dependencies: List<KaContextualTypeKey>,
  val ownerGraphId: GraphDeclarationId?,
  val additionalOwnerGraphIds: Set<GraphDeclarationId>,
  val isGraphInput: Boolean,
  val isBindingContainerInput: Boolean,
  val factoryDefinition: AssistedFactoryDefinitionSignature?,
)

private fun contextKeyLibrarySignature(key: KaContextualTypeKey): ContextKeyLibrarySignature =
  ContextKeyLibrarySignature(key, key.hasDefault, key.rawType)

/** Preserves default and raw-wrapper metadata omitted by contextual-key equality. */
internal data class ContextKeyLibrarySignature(
  val key: KaContextualTypeKey,
  val hasDefault: Boolean,
  val rawType: KaTypeSnapshot?,
)

/** Source factory metadata used by shard reuse and binary lookup cache keys. */
internal data class AssistedFactoryDefinitionSignature(
  val key: KaTypeKey,
  val originClassId: ClassId?,
  val file: VirtualFile?,
  val scope: KaAnnotationSnapshot?,
  val targetKey: KaTypeKey?,
  val dependencies: List<ContextKeyLibrarySignature>,
  val constructorDependencyCount: Int,
  val memberOwnerIds: Set<ClassId>,
  val functionName: String?,
  val functionIsSuspend: Boolean,
)

private data class FactoryInputLibrarySignature(
  val id: FactoryInputEntry.Id,
  val consumers: List<ConsumerLibrarySignature>,
  val writtenBindingKeys: List<KaTypeKey>,
  val bindings: List<BindingLibrarySignature>,
)
