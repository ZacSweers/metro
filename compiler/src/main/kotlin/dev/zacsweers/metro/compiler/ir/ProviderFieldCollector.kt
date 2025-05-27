// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.decapitalizeUS
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.util.kotlinFqName

private const val INITIAL_VALUE = 512

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

  private val nodes = HashMap<IrTypeKey, Node>(INITIAL_VALUE)

  fun collect(): Map<IrTypeKey, Binding> {
    val nodes = mutableMapOf<IrTypeKey, Node>()

    // Count references for each dependency
    for ((key, dependencies) in graph.adjacency) {
      val binding = graph.requireBinding(key, IrBindingStack.empty())
      // Ensure each key has a node
      nodes.getOrPut(key) { Node(binding) }
      for (dependency in dependencies) {
        dependency.mark()
      }
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
