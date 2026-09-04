// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.graph.LocationDiagnostic
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingIndex.SourcePointerIdentity
import dev.zacsweers.metro.idea.model.BindingIndexResolutionInputs
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.DeclarationDisplay
import dev.zacsweers.metro.idea.model.FrozenDeclarationResolutionScope
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import java.util.IdentityHashMap
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor

private typealias SourcePointer = SmartPsiElementPointer<out PsiElement>

/**
 * Source details for one graph seal. Names and locations come from the index generation. Lazy
 * request owners are captured under read access for this operation. Retained pointers serve as
 * identities and navigation targets.
 */
internal class ValidationSourceSnapshot
private constructor(
  private val declarations: List<BindingIndexResolutionInputs>,
  private val lazyRequests: Map<KaContextualTypeKey, LazyRequestSources>,
) {
  fun name(consumer: ConsumerEntry): String? = declaration(consumer.pointer)?.name

  fun location(binding: KaBinding): String? = declaration(binding.pointer)?.location

  fun locationDiagnostic(binding: KaBinding): LocationDiagnostic {
    return LocationDiagnostic(
      location(binding) ?: binding.typeKey.render(short = true),
      binding.renderDescriptionDiagnostic(short = true, underlineTypeKey = true),
    )
  }

  fun lazyRequestSources(request: KaContextualTypeKey): LazyRequestSources? = lazyRequests[request]

  /** Visible source sites grouped by the binding declarations that own their requests. */
  class LazyRequestSources(
    val byDeclaration: Map<SourcePointerIdentity, List<SourcePointer>>,
    val byOrigin: Map<ClassId, List<SourcePointer>>,
    val byMemberOwner: Map<ClassId, List<SourcePointer>>,
  )

  private fun declaration(pointer: SourcePointer): DeclarationDisplay? {
    return declarations.firstNotNullOfOrNull { it.declarationDisplay(pointer) }
  }

  /** A request site paired with its captured enclosing declarations. */
  private class LazySource(
    val consumer: ConsumerEntry,
    val pointer: SourcePointer,
    val declarations: Set<SourcePointerIdentity>,
  )

  companion object {
    /** Captures a single custom query view. Must be called under a read action. */
    fun capture(index: BindingIndex, queryContext: GraphQueryContext): ValidationSourceSnapshot {
      return capture(mapOf(index to listOf(queryContext))).getValue(index).getValue(queryContext)
    }

    /** Reuses display data from every index whose bindings can be reserved by this traversal. */
    fun capture(
      contextsByIndex: Map<BindingIndex, List<GraphQueryContext>>,
      displayIndexes: Collection<BindingIndex> = contextsByIndex.keys,
    ): Map<BindingIndex, Map<GraphQueryContext, ValidationSourceSnapshot>> {
      if (contextsByIndex.isEmpty()) return emptyMap()
      val declarations = displayIndexes.map { it.resolutionInputs }
      val snapshots =
        IdentityHashMap<BindingIndex, Map<GraphQueryContext, ValidationSourceSnapshot>>()
      for ((index, contexts) in contextsByIndex) {
        snapshots[index] = capture(index, contexts, declarations)
      }
      return snapshots
    }

    private fun capture(
      index: BindingIndex,
      queryContexts: List<GraphQueryContext>,
      declarations: List<BindingIndexResolutionInputs>,
    ): Map<GraphQueryContext, ValidationSourceSnapshot> {
      // Enclosing owners are needed only for requests that could produce a Lazy factory error.
      val factoryTypes = buildSet {
        for (binding in index.bindings) {
          ProgressManager.checkCanceled()
          if (binding is KaBinding.AssistedFactory) add(binding.typeKey.type)
        }
      }
      val sourcesByContext =
        IdentityHashMap<
          GraphQueryContext,
          MutableMap<KaContextualTypeKey, MutableList<LazySource>>,
        >()
      for (context in queryContexts) sourcesByContext[context] = linkedMapOf()
      val elements = IdentityHashMap<SourcePointer, PsiElement?>()
      fun element(pointer: SourcePointer): PsiElement? {
        if (!elements.containsKey(pointer)) elements[pointer] = pointer.element
        return elements[pointer]
      }
      for (consumer in index.consumers) {
        ProgressManager.checkCanceled()
        val request = consumer.contextKey
        if (!request.isWrappedInLazy || request.typeKey.type !in factoryTypes) continue
        val visibleContexts = queryContexts.filter { context ->
          ProgressManager.checkCanceled()
          val graphId = consumer.graphId
          val belongsToGraph = graphId == null || graphId in context.graphContext.graphIds
          if (!belongsToGraph) return@filter false
          val scope = context.resolutionScope
          if (scope is FrozenDeclarationResolutionScope) {
            scope.contains(consumer.sourceIdentity?.file ?: consumer.pointer.virtualFile)
          } else {
            val source = element(consumer.pointer) ?: return@filter false
            scope.contains(source)
          }
        }
        if (visibleContexts.isEmpty()) continue
        val sourceElement = element(consumer.pointer) ?: continue
        val pointer = consumer.injectedMemberPointer ?: consumer.pointer
        val owners = linkedSetOf<SourcePointerIdentity>()
        if (consumer.injectedMemberPointer != null) {
          index.pointerIdentity(consumer.pointer)?.let(owners::add)
        }
        // Provider parameters belong to their callable. Constructor bindings use their class,
        // and inherited members also retain their declaring class identity below.
        var owner: PsiElement? = element(pointer) ?: sourceElement
        while (owner != null) {
          ProgressManager.checkCanceled()
          val declaration =
            when (owner) {
              is KtPropertyAccessor -> owner.property
              is KtNamedFunction,
              is KtProperty,
              is KtConstructor<*>,
              is KtClassOrObject -> owner
              else -> null
            }
          declaration?.let(index::sourceIdentity)?.let(owners::add)
          if (owner is KtClassOrObject) break
          owner = owner.parent
        }
        val source = LazySource(consumer, pointer, owners)
        for (context in visibleContexts) {
          sourcesByContext.getValue(context).getAndAdd(request, source)
        }
      }

      val snapshots = IdentityHashMap<GraphQueryContext, ValidationSourceSnapshot>()
      for (queryContext in queryContexts) {
        ProgressManager.checkCanceled()
        // Group each request once so bindings sharing it do not rescan its consumer sites.
        val sourcesByRequest = sourcesByContext.getValue(queryContext)
        val lazyRequests = sourcesByRequest.mapValues { (_, sources) ->
          val owners = mutableMapOf<SourcePointerIdentity, MutableList<SourcePointer>>()
          val origins = mutableMapOf<ClassId, MutableList<SourcePointer>>()
          val memberOwners = mutableMapOf<ClassId, MutableList<SourcePointer>>()
          for (source in sources) {
            ProgressManager.checkCanceled()
            for (owner in source.declarations) owners.getAndAdd(owner, source.pointer)
            source.consumer.originClassId?.let { origins.getAndAdd(it, source.pointer) }
            source.consumer.memberOwnerClassId?.let { memberOwners.getAndAdd(it, source.pointer) }
          }
          LazyRequestSources(owners, origins, memberOwners)
        }
        snapshots[queryContext] = ValidationSourceSnapshot(declarations, lazyRequests)
      }
      return snapshots
    }
  }
}
