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
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import java.util.IdentityHashMap
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor

private typealias SourcePointer = SmartPsiElementPointer<out PsiElement>

/**
 * Source details for one graph seal. Locations, names, and enclosing declarations are captured
 * under read access. The retained pointers are used only as identities and navigation targets.
 */
internal class ValidationSourceSnapshot
private constructor(
  private val declarations: Map<SourcePointer, Declaration>,
  private val lazyRequests: Map<KaContextualTypeKey, LazyRequestSources>,
) {
  fun name(consumer: ConsumerEntry): String? = declarations[consumer.pointer]?.name

  fun location(binding: KaBinding): String? = declarations[binding.pointer]?.location

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

  private class Declaration(val name: String?, val location: String?)

  /** Live elements are confined to capture and discarded before snapshots are returned. */
  private class LazySource(
    val consumer: ConsumerEntry,
    val element: PsiElement,
    val pointer: SourcePointer,
    val declarations: Set<SourcePointerIdentity>,
  )

  companion object {
    /** Captures a single custom query view. Must be called under a read action. */
    fun capture(index: BindingIndex, queryContext: GraphQueryContext): ValidationSourceSnapshot {
      return capture(mapOf(index to listOf(queryContext))).getValue(index).getValue(queryContext)
    }

    /**
     * Reads each declaration once for all contexts in this operation. Shared locations also cover
     * bindings reserved by cached extension results whose index needs no new seal.
     */
    fun capture(
      contextsByIndex: Map<BindingIndex, List<GraphQueryContext>>,
      reservedBindings: Iterable<KaBinding> = emptyList(),
    ): Map<BindingIndex, Map<GraphQueryContext, ValidationSourceSnapshot>> {
      val declarations = IdentityHashMap<SourcePointer, Declaration>()
      val elements = IdentityHashMap<SourcePointer, PsiElement?>()
      for (binding in reservedBindings) captureDeclaration(binding.pointer, declarations, elements)
      val snapshots =
        IdentityHashMap<BindingIndex, Map<GraphQueryContext, ValidationSourceSnapshot>>()
      for ((index, contexts) in contextsByIndex) {
        snapshots[index] = capture(index, contexts, declarations, elements)
      }
      return snapshots
    }

    /**
     * Missing declarations keep their unknown-location fallback; vanished Lazy sites are omitted.
     */
    private fun captureDeclaration(
      pointer: SourcePointer,
      declarations: IdentityHashMap<SourcePointer, Declaration>,
      elements: IdentityHashMap<SourcePointer, PsiElement?>,
    ): PsiElement? {
      ProgressManager.checkCanceled()
      if (declarations.containsKey(pointer)) return elements[pointer]
      val element = pointer.element
      elements[pointer] = element
      val file = element?.containingFile
      val document = file?.viewProvider?.document
      val line = element?.let { document?.getLineNumber(it.textOffset)?.plus(1) }
      val location = if (line == null) file?.name else "${file?.name}:$line"
      declarations[pointer] = Declaration((element as? KtNamedDeclaration)?.name, location)
      return element
    }

    private fun capture(
      index: BindingIndex,
      queryContexts: List<GraphQueryContext>,
      declarations: IdentityHashMap<SourcePointer, Declaration>,
      elements: IdentityHashMap<SourcePointer, PsiElement?>,
    ): Map<GraphQueryContext, ValidationSourceSnapshot> {
      fun capture(pointer: SourcePointer) = captureDeclaration(pointer, declarations, elements)

      for (binding in index.bindings) capture(binding.pointer)
      for (consumer in index.consumers) capture(consumer.pointer)
      // Bindings synthesized during sealing reuse these declaration pointers.
      for (graph in index.graphs) {
        capture(graph.pointer)
        for (factory in graph.extensionFactories) capture(factory.pointer)
        for (contribution in graph.contributedInterfaces) {
          for (binding in contribution.bindings) capture(binding.pointer)
          for (consumer in contribution.consumers) capture(consumer.pointer)
          for (factory in contribution.extensionFactories) capture(factory.pointer)
        }
      }
      for (dynamicGraph in index.dynamicGraphs) {
        capture(dynamicGraph.pointer)
        for (input in dynamicGraph.containerInputs) capture(input.pointer)
      }

      // Enclosing owners are needed only for requests that could produce a Lazy factory error.
      val factoryTypes = buildSet {
        for (binding in index.bindings) {
          ProgressManager.checkCanceled()
          if (binding is KaBinding.AssistedFactory) add(binding.typeKey.type)
        }
      }
      val lazySources = mutableListOf<LazySource>()
      for (consumer in index.consumers) {
        ProgressManager.checkCanceled()
        val request = consumer.contextKey
        if (!request.isWrappedInLazy || request.typeKey.type !in factoryTypes) continue
        val element = capture(consumer.pointer) ?: continue
        val pointer = consumer.injectedMemberPointer ?: consumer.pointer
        val owners = linkedSetOf<SourcePointerIdentity>()
        if (consumer.injectedMemberPointer != null) {
          index.pointerIdentity(consumer.pointer)?.let(owners::add)
        }
        // Provider parameters belong to their callable. Constructor bindings use their class,
        // and inherited members also retain their declaring class identity below.
        var owner: PsiElement? = capture(pointer) ?: element
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
        lazySources += LazySource(consumer, element, pointer, owners)
      }

      val snapshots = IdentityHashMap<GraphQueryContext, ValidationSourceSnapshot>()
      for (queryContext in queryContexts) {
        ProgressManager.checkCanceled()
        // Group each request once so bindings sharing it do not rescan its consumer sites.
        val sourcesByRequest = linkedMapOf<KaContextualTypeKey, MutableList<LazySource>>()
        for (source in lazySources) {
          ProgressManager.checkCanceled()
          val graphId = source.consumer.graphId
          if (graphId != null && graphId !in queryContext.graphContext.graphIds) continue
          if (!queryContext.resolutionScope.contains(source.element)) continue
          sourcesByRequest.getAndAdd(source.consumer.contextKey, source)
        }
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
