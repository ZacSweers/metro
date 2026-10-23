// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.transformers.ValidationResult

/** Walks sealed graphs so inherited requests can inspect the binding in its owning graph. */
context(context: IrMetroContext)
internal fun reportLifecycleWarnings(root: ValidationResult) {
  fun visit(graph: ValidationResult, ancestors: List<ValidationResult>) {
    for (callback in graph.node.lifecycleCallbacks) {
      for (parameter in callback.parameters) {
        if (parameter.key.wrappedType.isDeferrable()) {
          continue
        }
        val binding = graph.effectiveLifecycleBinding(parameter.key.typeKey, ancestors) ?: continue
        val stable = binding.isScoped() || binding is IrBinding.BoundInstance
        if (stable || binding is IrBinding.ObjectClass) {
          continue
        }
        val type = parameter.key.render(short = true, includeQualifier = true)
        val name = callback.declaration.name
        context.reportCompat(
          sequenceOf(parameter.source, graph.node.sourceGraph),
          MetroDiagnostics.UNSCOPED_LIFECYCLE_PARAMETER,
          "[${MetroDiagnosticId.UNSCOPED_LIFECYCLE_PARAMETER.fullId}] Parameter '${parameter.source.name}' of '$name' requests unscoped $type. Request a provider or lazy value to control when the instance is created.",
        )
      }
    }
    for (child in graph.childValidationResults) {
      visit(child, ancestors + graph)
    }
  }
  visit(root, emptyList())
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
