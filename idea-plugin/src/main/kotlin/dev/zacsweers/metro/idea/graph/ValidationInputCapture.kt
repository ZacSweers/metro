// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingResolutionSession
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.psi.KtFile

/** One captured graph or a reusable result from the same index generation. */
internal sealed interface ValidationInput {
  val index: BindingIndex
  val context: GraphContext

  class Cached(
    override val index: BindingIndex,
    override val context: GraphContext,
    val result: KaGraphValidationResult,
  ) : ValidationInput

  /** Children and parent lookups already use the compilation that owns each declaration. */
  class Unsealed(
    val session: BindingResolutionSession,
    val queryContext: GraphQueryContext,
    val options: MetroOptions,
    val sources: ValidationSourceSnapshot,
    val children: List<ValidationInput>,
    val parents: Map<GraphPath, ParentGraphLookup>,
  ) : ValidationInput {
    override val index: BindingIndex
      get() = session.index

    override val context: GraphContext
      get() = queryContext.graphContext
  }
}

/** Child-first graph inputs with no live PSI reads remaining. Sessions belong to this traversal. */
internal class ValidationTraversal(val requestPath: GraphPath, val inputs: List<ValidationInput>)

/**
 * Resolves current graph paths and copies their source details under a read action. One instance
 * belongs to one validation request. Capture retries discard all operation-local sessions.
 */
internal class ValidationInputCapture(
  private val project: Project,
  private val cachedResult: (GraphPath, IndexGenerationToken) -> KaGraphValidationResult? =
    { _, _ ->
      null
    },
  private val allowIndexBuild: Boolean = true,
) {
  private val sessions = IdentityHashMap<BindingIndex, BindingResolutionSession>()

  /** A PSI-bearing input used only while capture is running. */
  private class ResolvedInput(
    val element: PsiElement,
    val session: BindingResolutionSession,
    val context: GraphContext,
  )

  private class PendingInput(
    val session: BindingResolutionSession,
    val queryContext: GraphQueryContext,
    val options: MetroOptions,
    val cached: KaGraphValidationResult?,
    val children: List<PendingInput>,
  )

  /** Captures one concrete path, including children needed for its parent reservations. */
  fun capture(
    element: PsiElement,
    context: GraphContext,
    includeExtensions: Boolean,
  ): ValidationTraversal {
    return capture(element, context.path, listOf(context), includeExtensions)
  }

  /** Captures every concrete context and extension of a declaration. */
  fun capture(element: PsiElement, graph: KaGraphDeclaration): ValidationTraversal {
    val declarationElement = sourceElement(graph.pointer.element) ?: element
    val index = indexFor(declarationElement)
    val currentGraph =
      index.graphFor(graph)
        ?: throw CancellationException("Metro graph declaration is no longer current")
    return capture(
      declarationElement,
      GraphPath(listOf(currentGraph.declarationId)),
      session(index).contextsFor(currentGraph),
      includeExtensions = true,
    )
  }

  /** Used by read-action lookup inspection as well as eager parent capture. */
  fun lookup(element: PsiElement, context: GraphContext): ParentGraphLookup? {
    val input = resolve(element, context) ?: return null
    val queryContext = input.session.queryContext(input.context) ?: return null
    return ParentGraphLookup(input.session, queryContext, moduleOptions(input.element))
  }

  private fun capture(
    declarationFallback: PsiElement,
    requestPath: GraphPath,
    rootContexts: List<GraphContext>,
    includeExtensions: Boolean,
  ): ValidationTraversal {
    val ordered = mutableListOf<PendingInput>()
    val visited = linkedMapOf<GraphPath, PendingInput>()
    val parents = linkedMapOf<GraphPath, ParentGraphLookup>()

    fun captureParents(
      input: ResolvedInput,
      queryContext: GraphQueryContext,
      options: MetroOptions,
    ) {
      var child = ParentGraphLookup(input.session, queryContext, options)
      while (child.queryContext.graphContext.chain.size > 1) {
        ProgressManager.checkCanceled()
        val context = child.queryContext.graphContext
        val path = GraphPath(context.path.segments.drop(1), context.path.dynamicGraphId)
        val parentContext = child.session.findContext(path) ?: break
        val parent = parents[path] ?: lookup(input.element, parentContext) ?: break
        parents[path] = parent
        child = parent
      }
    }

    fun visit(context: GraphContext, required: Boolean): PendingInput? {
      ProgressManager.checkCanceled()
      visited[context.path]?.let {
        return it
      }
      val input = resolve(declarationFallback, context)
      if (input == null) {
        if (!required) return null
        throw CancellationException("Metro graph context is no longer available")
      }
      val current = input.context
      val queryContext =
        input.session.queryContext(current)
          ?: throw CancellationException("Metro graph declaration is no longer current")
      val cached = cachedResult(current.path, input.session.index.generationToken)
      val children = mutableListOf<PendingInput>()
      if (includeExtensions || cached == null) {
        for (extension in input.session.extensionContextsOf(current)) {
          ProgressManager.checkCanceled()
          visit(extension, required = includeExtensions)?.let(children::add)
        }
      }
      val options = moduleOptions(input.element)
      if (cached == null) captureParents(input, queryContext, options)
      val pending = PendingInput(input.session, queryContext, options, cached, children)
      visited[current.path] = pending
      ordered += pending
      return pending
    }

    for (context in rootContexts) visit(context, required = true)

    val contextsByIndex = IdentityHashMap<BindingIndex, MutableList<GraphQueryContext>>()
    for (input in ordered) {
      ProgressManager.checkCanceled()
      if (input.cached != null) continue
      contextsByIndex.getOrPut(input.session.index, ::mutableListOf) += input.queryContext
    }
    val displayIndexes = ordered.mapTo(linkedSetOf()) { it.session.index }
    parents.values.mapTo(displayIndexes) { it.index }
    val sourcesByIndex = ValidationSourceSnapshot.capture(contextsByIndex, displayIndexes)

    val captured = IdentityHashMap<PendingInput, ValidationInput>()
    val capturedParents = parents.toMap()
    val inputs = ordered.map { pending ->
      ProgressManager.checkCanceled()
      val index = pending.session.index
      val cached = pending.cached
      val input =
        if (cached != null) {
          ValidationInput.Cached(index, pending.queryContext.graphContext, cached)
        } else {
          ValidationInput.Unsealed(
            pending.session,
            pending.queryContext,
            pending.options,
            sourcesByIndex.getValue(index).getValue(pending.queryContext),
            pending.children.map(captured::getValue),
            capturedParents,
          )
        }
      captured[pending] = input
      input
    }
    return ValidationTraversal(requestPath, inputs)
  }

  private fun session(index: BindingIndex): BindingResolutionSession {
    return sessions.getOrPut(index) { index.createResolutionSession() }
  }

  private fun resolve(declarationFallback: PsiElement, context: GraphContext): ResolvedInput? {
    val element = compilationElement(context) ?: declarationFallback
    val index = indexFor(element)
    val session = session(index)
    val current = session.findContext(context.path) ?: return null
    val currentElement = compilationElement(current) ?: element
    return ResolvedInput(currentElement, session, current)
  }

  /** Automatic capture waits for publication when an edit invalidates its cached generation. */
  private fun indexFor(element: PsiElement): BindingIndex {
    val resolution = project.service<MetroResolutionService>()
    if (allowIndexBuild) return resolution.currentIndex(element)
    val index = resolution.cachedIndex(element)
    if (index === BindingIndex.EMPTY) {
      throw CancellationException("Metro graph data needs a refresh")
    }
    return index
  }

  /** Compiled extensions are validated in the source compilation that creates their path. */
  private fun compilationElement(context: GraphContext): PsiElement? {
    sourceElement(context.dynamicGraph?.pointer?.element)?.let {
      return it
    }
    for (graph in context.chain) {
      sourceElement(graph.pointer.element)?.let {
        return it
      }
    }
    return null
  }

  private fun sourceElement(element: PsiElement?): PsiElement? {
    val file = element?.containingFile as? KtFile ?: return null
    return element.takeUnless { file.isCompiled }
  }

  private fun moduleOptions(element: PsiElement): MetroOptions {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return MetroOptions()
    return project.service<MetroIdeProjectService>().state(module).options
  }
}
