// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.transformers.ValidationResult

/** Walks sealed graphs so inherited requests can inspect the binding in its owning graph. */
context(context: IrMetroContext)
internal fun reportLifecycleWarnings(root: ValidationResult) {
  val ordinaryUses by lazy { root.bindingsUsedOutsideClose() }
  fun visit(graph: ValidationResult, ancestors: List<ValidationResult>) {
    for (callback in graph.node.lifecycleCallbacks) {
      for (parameter in callback.parameters) {
        val binding = graph.effectiveLifecycleBinding(parameter.key.typeKey, ancestors) ?: continue
        val type = parameter.key.render(short = true, includeQualifier = true)
        val source = sequenceOf(parameter.source, graph.node.sourceGraph)
        val description = "Parameter '${parameter.source.name}' of '${callback.declaration.name}'"
        val stable = binding.isScoped() || binding is IrBinding.BoundInstance
        val unscoped = !stable && binding !is IrBinding.ObjectClass
        if (unscoped && !parameter.key.wrappedType.isDeferrable()) {
          context.reportCompat(
            source,
            MetroDiagnostics.UNSCOPED_LIFECYCLE_PARAMETER,
            "[${MetroDiagnosticId.UNSCOPED_LIFECYCLE_PARAMETER.fullId}] $description requests unscoped $type. Request a provider or lazy value to control when the instance is created.",
          )
        }
        val external = binding is IrBinding.BoundInstance || binding is IrBinding.GraphDependency
        if (callback.phase == GraphLifecyclePhase.CLOSE && !external) {
          if (binding !in ordinaryUses) {
            context.reportCompat(
              source,
              MetroDiagnostics.CLOSE_ONLY_BINDING,
              "[${MetroDiagnosticId.CLOSE_ONLY_BINDING.fullId}] $description can create $type for the first time during graph close.",
            )
          }
        }
      }
    }
    for (child in graph.childValidationResults) {
      visit(child, ancestors + graph)
    }
  }
  visit(root, emptyList())
}

/** Seeds reachability from actual requests. Shrinking can retain unrelated declarations. */
private fun ValidationResult.bindingsUsedOutsideClose(): Set<IrBinding> {
  val ordinary =
    java.util.Collections.newSetFromMap(java.util.IdentityHashMap<IrBinding, Boolean>())
  fun visit(graph: ValidationResult, ancestors: List<ValidationResult>) {
    val closeOnly = mutableSetOf<IrTypeKey>()
    fun walk(start: IrTypeKey, duringClose: Boolean) {
      val pending = ArrayDeque<Pair<ValidationResult, IrTypeKey>>()
      pending.add(graph to start)
      while (pending.isNotEmpty()) {
        val (owner, key) = pending.removeLast()
        val binding = owner.bindingGraph.findBinding(key) ?: continue
        // A request from a child counts as a use in its owner.
        val fresh =
          if (duringClose && owner === graph) {
            closeOnly.add(key)
          } else {
            ordinary.add(binding)
          }
        if (!fresh) {
          continue
        }
        val token = binding.parentToken
        if (token != null) {
          val parent = ancestors.lastOrNull { it.node.typeKey == token.ownerGraphKey }
          if (parent != null) {
            pending.add(parent to token.contextKey.typeKey)
          }
        } else {
          for (dependency in binding.dependencies) {
            pending.add(owner to dependency.typeKey)
          }
        }
      }
    }
    for (root in graph.node.accessors + graph.node.injectors) {
      walk(root.contextKey.typeKey, duringClose = false)
    }
    for (callback in graph.node.lifecycleCallbacks) {
      for (parameter in callback.parameters) {
        walk(parameter.key.typeKey, duringClose = callback.phase == GraphLifecyclePhase.CLOSE)
      }
    }
    for (child in graph.childValidationResults) {
      visit(child, ancestors + graph)
    }
  }
  visit(this, emptyList())
  return ordinary
}

/** Follows aliases and inherited bindings without performing new lookups after shrinking. */
private fun ValidationResult.effectiveLifecycleBinding(
  key: IrTypeKey,
  ancestors: List<ValidationResult>,
): IrBinding? {
  var graph = this
  var current = key
  while (true) {
    val binding = graph.bindingGraph.findBinding(current) ?: return null
    val owner = binding.parentToken
    if (owner != null) {
      graph = ancestors.lastOrNull { it.node.typeKey == owner.ownerGraphKey } ?: return null
      current = owner.contextKey.typeKey
    } else if (binding is IrBinding.Alias) {
      current = binding.aliasedType
    } else {
      return binding
    }
  }
}
