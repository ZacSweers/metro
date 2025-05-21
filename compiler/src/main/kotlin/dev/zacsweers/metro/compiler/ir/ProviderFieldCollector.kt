// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.decapitalizeUS
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.util.kotlinFqName

/** Computes the set of bindings that must end up in provider fields. */
internal class ProviderFieldCollector(
  private val node: DependencyGraphNode,
  private val graph: IrBindingGraph,
  private val onError: (IrDeclaration, String) -> Nothing,
) {

  private data class Node(val binding: Binding, var refCount: Int = 0) {
    val needsField: Boolean
      get() {
        // Scoped, graph, and members injector bindings always need provider fields
        if (binding.scope != null) return true
        if (binding is Binding.GraphDependency) return true
        if (binding is Binding.MembersInjected && !binding.isFromInjectorFunction) return true
        // Multibindings are always created adhoc
        if (binding is Binding.Multibinding) return false

        // If it's unscoped but used more than once and not into a multibinding,
        // we can generate a reusable field
        if (refCount < 2) return false
        val isMultibindingProvider =
          (binding is Binding.BindingWithAnnotations) && binding.annotations.isIntoMultibinding
        return !isMultibindingProvider
      }

    /** @return true if we’ve referenced this binding before. */
    fun mark(): Boolean {
      refCount++
      return refCount > 1
    }
  }

  private val nodes = HashMap<IrTypeKey, Node>(128)

  fun collect(): Map<IrTypeKey, Binding> {
    processNodes()

    if (node.isExtendable) {
      // Ensure all scoped providers have fields in extendable graphs, even if they are not used in
      // this graph
      graph
        .bindingsSnapshot()
        .values
        .filterIsInstance<Binding.Provided>()
        .filter { it.annotations.isScoped }
        .forEach { it.mark() }
    }

    // Decide which bindings actually need provider fields
    return buildMap(nodes.size) {
      for ((key, node) in nodes) {
        val binding = node.binding
        if (node.needsField) {
          put(key, binding)
        }
      }
    }
  }

  private fun processNodes() {
    // one set for all the visited bookkeeping
    val seen = HashSet<IrTypeKey>(500)
    val queue =
      ArrayDeque<Pair<IrContextualTypeKey, IrBindingStack>>(
        (node.accessors.size + node.injectors.size) * 4
      )

    // TODO use graph.bindingSnapshot() directly
    node.accessors
      .plus(node.injectors.map { (injector, key) -> injector to IrContextualTypeKey(key) })
      .forEach { (injector, contextKey) ->
        if (seen.add(contextKey.typeKey)) {
          val entry = IrBindingStack.Entry.requestedAt(contextKey, injector.ir)
          queue +=
            contextKey to IrBindingStack(node.sourceGraph, MetroLogger.NONE).apply { push(entry) }
        } else {
          // Increment the refCount still
          contextKey.typeKey.mark()
        }
      }

    while (queue.isNotEmpty()) {
      val (contextKey, stack) = queue.removeFirst()
      val binding = graph.requireBinding(contextKey, stack)
      if (binding.mark()) {
        // already processed this binding
        continue
      }

      binding.checkScope(stack)

      // Enqueue dependencies
      // TODO just read dependencies instead and look up from graph?
      val successors =
        when (binding) {
          is Binding.Assisted -> {
            sequenceOf(binding.target to stack)
          }
          is Binding.Multibinding -> {
            binding.sourceBindings.asSequence().map { IrContextualTypeKey(it) to stack }
          }
          else -> {
            binding.parameters.nonInstanceParameters
              .asSequence()
              .filterNot { it.isAssisted }
              .map { it.contextualTypeKey to stack.copy().apply { push(it.bindingStackEntry) } }
          }
        }

      successors.forEach { (contextKey, successorStack) ->
        if (seen.add(contextKey.typeKey)) {
          queue += contextKey to successorStack
        } else {
          // Increment the refCount still
          contextKey.typeKey.mark()
        }
      }
    }
  }

  private fun IrTypeKey.mark(): Boolean {
    val binding = graph.requireBinding(this, IrBindingStack.empty())
    return binding.mark()
  }

  private fun Binding.mark(): Boolean {
    val node = nodes.getOrPut(typeKey) { Node(this) }
    return node.mark()
  }

  // Check scoping compatibility
  // TODO FIR error?
  private fun Binding.checkScope(stack: IrBindingStack) {
    val bindingScope = scope
    if (bindingScope != null) {
      if (node.scopes.isEmpty() || bindingScope !in node.scopes) {
        val isUnscoped = node.scopes.isEmpty()
        // Error if there are mismatched scopes
        val declarationToReport = node.sourceGraph
        val binding = this
        stack.push(
          IrBindingStack.Entry.simpleTypeRef(
            binding.contextualTypeKey,
            usage = "(scoped to '$bindingScope')",
          )
        )
        val message = buildString {
          append("[Metro/IncompatiblyScopedBindings] ")
          append(declarationToReport.kotlinFqName)
          if (isUnscoped) {
            // Unscoped graph but scoped binding
            append(" (unscoped) may not reference scoped bindings:")
          } else {
            // Scope mismatch
            append(
              " (scopes ${node.scopes.joinToString { "'$it'" }}) may not reference bindings from different scopes:"
            )
          }
          appendLine()
          appendBindingStack(stack, short = false)
          if (!isUnscoped && binding is Binding.ConstructorInjected) {
            val matchingParent =
              node.allExtendedNodes.values.firstOrNull { bindingScope in it.scopes }
            if (matchingParent != null) {
              appendLine()
              appendLine()
              val shortTypeKey = binding.typeKey.render(short = true)
              appendLine(
                """
                  (Hint)
                  It appears that extended parent graph '${matchingParent.sourceGraph.kotlinFqName}' does declare the '$bindingScope' scope but doesn't use '$shortTypeKey' directly.
                  To work around this, consider declaring an accessor for '$shortTypeKey' in that graph (i.e. `val ${shortTypeKey.decapitalizeUS()}: $shortTypeKey`).
                  See https://github.com/ZacSweers/metro/issues/377 for more details.
                """
                  .trimIndent()
              )
            }
          }
        }
        onError(declarationToReport, message)
      }
    }
  }
}
