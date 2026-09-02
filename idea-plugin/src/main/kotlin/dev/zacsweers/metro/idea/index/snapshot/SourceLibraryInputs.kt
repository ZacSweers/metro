// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.idea.index.ConsumerOwnershipBundle
import dev.zacsweers.metro.idea.index.SourceAssistedFactoryPostProcessor
import dev.zacsweers.metro.idea.index.SourceFactoryResolution
import dev.zacsweers.metro.idea.index.sourceFactoryIdentity
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.SourceAssistedFactoryIdentity
import java.util.Collections
import java.util.IdentityHashMap
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement

/** Captures source ownership, factory expansion, and library seeds in the snapshot read action. */
internal fun buildFinalizedSourceLibrarySummary(
  project: Project,
  source: SourceAggregate,
  sourceIndex: BindingIndex,
): FinalizedSourceLibrarySummary {
  val consumerOwnership = ConsumerOwnershipBundle.build(sourceIndex)
  val sourceFactories =
    SourceAssistedFactoryPostProcessor(
        project,
        source.bindings,
        source.consumers,
        consumerOwnership,
      )
      .resolveInitial()
  val completeSource = source.withAddedFactories(sourceFactories.addedBindings)
  val inputs = completeSource.libraryInputs(project, sourceFactories, consumerOwnership)
  ProgressManager.checkCanceled()
  return FinalizedSourceLibrarySummary(inputs, consumerOwnership, sourceFactories)
}

/** Reused while effective library lookup inputs and source-module ownership remain unchanged. */
internal data class FinalizedSourceLibrarySummary(
  val inputs: LibraryInputs,
  val consumerOwnership: ConsumerOwnershipBundle,
  val sourceFactories: SourceFactoryResolution,
)

/** Resolves requesting modules and source-factory seeds while source pointers remain readable. */
private fun SourceAggregate.libraryInputs(
  project: Project,
  sourceFactories: SourceFactoryResolution,
  consumerOwnership: ConsumerOwnershipBundle,
): LibraryInputs {
  val sourceFactoryUseSites = sourceFactories.factoryUseSites
  val scopeIds = linkedSetOf<ClassId>()
  val participatingModules = linkedSetOf<KaModule>()
  val injectRequests = linkedSetOf<LibraryInjectInput>()
  val seededFactoryUseSites =
    if (sourceFactoryUseSites.isEmpty()) null
    else {
      Collections.newSetFromMap(
        IdentityHashMap<Map<KaModule, SmartPsiElementPointer<out KtElement>>, Boolean>()
      )
    }

  fun addModule(element: PsiElement?): KaModule? {
    if (element !is KtElement) return null
    return KaModuleProvider.getModule(project, element, useSiteModule = null).also {
      participatingModules += it
    }
  }

  for (graph in graphs) {
    ProgressManager.checkCanceled()
    scopeIds += graph.scopeKeys
    addModule(graph.pointer.element)
  }
  for (dynamicGraph in dynamicGraphs) {
    ProgressManager.checkCanceled()
    addModule(dynamicGraph.pointer.element)
  }
  for (contribution in contributions) {
    ProgressManager.checkCanceled()
    scopeIds += contribution.scopeKeys
    addModule(contribution.pointer.element)
  }
  for (consumer in consumers) {
    ProgressManager.checkCanceled()
    val classId = consumer.typeClassId
    val containerOwners = consumerOwnership.owningGraphPointers(consumer)
    if (containerOwners == null) {
      val module = addModule(consumerOwnership.pointer(consumer).element) ?: continue
      if (classId == null || consumer.multibindingId != null) continue
      injectRequests += LibraryInjectInput(module, consumer.key, classId)
    } else {
      for (owner in containerOwners) {
        val module = addModule(owner.element) ?: continue
        if (classId == null || consumer.multibindingId != null) continue
        injectRequests += LibraryInjectInput(module, consumer.key, classId)
      }
    }
  }
  for (binding in bindings) {
    ProgressManager.checkCanceled()
    val hasAdditionalLibrarySeeds =
      binding is KaBinding.AssistedFactory ||
        binding is KaBinding.Provided && binding.isClassContribution ||
        binding is KaBinding.Alias && binding.isClassContribution
    if (!hasAdditionalLibrarySeeds || binding.dependencies.isEmpty()) continue
    if (binding is KaBinding.AssistedFactory) {
      val requestingUseSites = sourceFactoryUseSites[binding]
      if (requestingUseSites != null && seededFactoryUseSites?.add(requestingUseSites) == false) {
        continue
      }
      val requestingModules = requestingUseSites?.keys
      if (!requestingModules.isNullOrEmpty()) {
        participatingModules += requestingModules
        for (module in requestingModules) {
          for (dependency in binding.dependencies) {
            val key = dependency.typeKey
            val classId = key.type.classId ?: continue
            injectRequests += LibraryInjectInput(module, key, classId)
          }
        }
        continue
      }
    }
    val module = addModule(binding.pointer.element) ?: continue
    for (dependency in binding.dependencies) {
      val key = dependency.typeKey
      val classId = key.type.classId ?: continue
      injectRequests += LibraryInjectInput(module, key, classId)
    }
  }
  val definitions = linkedMapOf<SourceAssistedFactoryIdentity, AssistedFactoryDefinitionSignature>()
  for (binding in bindings) {
    if (binding !is KaBinding.AssistedFactory) continue
    val identity = binding.sourceFactoryIdentity() ?: continue
    definitions.putIfAbsent(identity, assistedFactoryDefinitionSignature(binding))
  }
  val budget = sourceFactories.budget
  return LibraryInputs(
    scopeIds,
    participatingModules,
    injectRequests,
    definitions.values.toList(),
    FactoryBudgetCacheInput(budget.writtenDepth, budget.writtenNodes, budget.writtenFactoryKeys),
  )
}

/** Captured semantic inputs to the coordinator-owned binary-shard cache. */
internal data class LibraryInputs(
  val scopeIds: Set<ClassId>,
  val participatingModules: Set<KaModule>,
  val requests: Set<LibraryInjectInput>,
  val sourceFactoryDefinitions: List<AssistedFactoryDefinitionSignature>,
  val factoryBudget: FactoryBudgetCacheInput,
)

/** Written-source expansion limits included in the binary-shard cache key. */
internal data class FactoryBudgetCacheInput(
  val writtenDepth: Int,
  val writtenNodes: Int,
  val writtenFactoryKeys: Set<KaTypeKey>,
)

/** One injection seed resolved from its exact requesting module. */
internal data class LibraryInjectInput(
  val module: KaModule,
  val key: KaTypeKey,
  val classId: ClassId,
)
