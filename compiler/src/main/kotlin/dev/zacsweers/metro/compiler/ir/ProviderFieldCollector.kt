package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.decapitalizeUS
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.util.kotlinFqName

internal class ProviderFieldCollector(
  private val node: DependencyGraphNode,
  private val graph: IrBindingGraph,
  private val bindingStack: IrBindingStack,
  private val onError: (IrDeclaration, String) -> Nothing,
) {
  private val bindingDependencies = mutableMapOf<IrTypeKey, Binding>()
  // Track used unscoped bindings. We only need to generate a field if they're used more than
  // once
  private val usedUnscopedBindings = mutableSetOf<IrTypeKey>()
  private val visitedBindings = mutableSetOf<IrTypeKey>()

  fun collect(): Map<IrTypeKey, Binding> {
    // Collect from roots
    node.accessors.forEach { (accessor, contextualTypeKey) ->
      findAndProcessBinding(
        contextKey = contextualTypeKey,
        stackEntry = IrBindingStack.Entry.requestedAt(contextualTypeKey, accessor.ir),
      )
    }
    node.injectors.forEach { (accessor, typeKey) ->
      val contextKey = IrContextualTypeKey(typeKey)
      findAndProcessBinding(
        contextKey = contextKey,
        stackEntry = IrBindingStack.Entry.requestedAt(contextKey, accessor.ir),
      )
    }

    if (node.isExtendable) {
      // Ensure all scoped providers have fields in extendable graphs, even if they are not used in
      // this graph
      graph.bindingsSnapshot().forEach { (_, binding) ->
        if (binding is Binding.Provided && binding.annotations.isScoped) {
          processBinding(binding)
        }
      }
    }

    return bindingDependencies
  }

  private fun findAndProcessBinding(
    contextKey: IrContextualTypeKey,
    stackEntry: IrBindingStack.Entry,
  ) {
    val key = contextKey.typeKey
    // Skip if already visited
    if (key in visitedBindings) {
      if (key in usedUnscopedBindings && key !in bindingDependencies) {
        // Only add unscoped binding provider fields if they're used more than once
        bindingDependencies[key] = graph.requireBinding(key, bindingStack)
      }
      return
    }

    bindingStack.withEntry(stackEntry) {
      val binding = graph.requireBinding(contextKey, bindingStack)
      processBinding(binding)
    }
  }

  private fun processBinding(binding: Binding) {
    val isMultibindingProvider =
      (binding is Binding.Provided || binding is Binding.Alias) &&
        binding.annotations.isIntoMultibinding
    val key = binding.typeKey

    // Skip if already visited
    // TODO de-dupe with findAndProcessBinding
    if (!isMultibindingProvider && key in visitedBindings) {
      if (key in usedUnscopedBindings && key !in bindingDependencies) {
        // Only add unscoped binding provider fields if they're used more than once
        bindingDependencies[key] = graph.requireBinding(key, bindingStack)
      }
      return
    }

    val bindingScope = binding.scope

    // Check scoping compatibility
    // TODO FIR error?
    if (bindingScope != null) {
      if (node.scopes.isEmpty() || bindingScope !in node.scopes) {
        val isUnscoped = node.scopes.isEmpty()
        // Error if there are mismatched scopes
        val declarationToReport = node.sourceGraph
        bindingStack.push(
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
          appendBindingStack(bindingStack, short = false)
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

    visitedBindings += key

    // Scoped, graph, and members injector bindings always need (provider) fields
    if (
      bindingScope != null ||
        binding is Binding.GraphDependency ||
        (binding is Binding.MembersInjected && !binding.isFromInjectorFunction)
    ) {
      bindingDependencies[key] = binding
    }

    when (binding) {
      is Binding.Assisted -> {
        // For assisted bindings, we need provider fields for the assisted factory impl type
        // The factory impl type depends on a provider of the assisted type
        val targetBinding = graph.requireBinding(binding.target, bindingStack)
        bindingDependencies[key] = targetBinding
        // TODO is this safe to end up as a provider field? Can someone create a
        //  binding such that you have an assisted type on the DI graph that is
        //  provided by a provider that depends on the assisted factory? I suspect
        //  yes, so in that case we should probably track a separate field mapping
        usedUnscopedBindings += binding.target.typeKey
        // By definition, assisted parameters are not available on the graph
        // But we _do_ need to process the target type's parameters!
        processBinding(binding = targetBinding)
        return
      }

      is Binding.Multibinding -> {
        // For multibindings, we depend on anything the delegate providers depend on
        if (bindingScope != null) {
          // This is scoped so we want to keep an instance
          // TODO are these allowed?
          //  bindingDependencies[key] = buildMap {
          //    for (provider in binding.providers) {
          //      putAll(provider.dependencies)
          //    }
          //  }
        } else {
          // Process all providers deps, but don't need a specific dep for this one
          // TODO eventually would be nice to just let a binding.dependencies lookup handle this
          //  but currently the later logic uses parameters for lookups
          for (providerKey in binding.sourceBindings) {
            val provider = graph.requireBinding(providerKey, bindingStack)
            processBinding(binding = provider)
          }
        }
        return
      }

      else -> {
        // Do nothing here
      }
    }

    // Track dependencies before creating fields
    if (bindingScope == null) {
      usedUnscopedBindings += key
    }

    // Recursively process dependencies
    for (param in binding.parameters.nonInstanceParameters) {
      if (param.isAssisted) continue

      // Process binding dependencies
      findAndProcessBinding(
        contextKey = param.contextualTypeKey,
        stackEntry = param.bindingStackEntry,
      )
    }
  }
}
