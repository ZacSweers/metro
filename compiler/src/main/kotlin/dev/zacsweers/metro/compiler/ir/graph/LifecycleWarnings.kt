// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSection
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.diagnostics.buildText
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.graph.toText
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.padForConsole
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.ir.reportCompat
import org.jetbrains.kotlin.ir.util.parentAsClass

/**
 * Warns about lifecycle callback parameters in one sealed graph.
 *
 * [rootsOutsideClose] are the keys requested outside graph close, including the keys child graphs
 * read from this graph. Requests from children count as uses, so bindings a child reads from a
 * parent never trigger the close-only warning.
 */
context(context: IrMetroContext)
internal fun reportLifecycleWarnings(
  node: GraphNode.Local,
  graph: IrBindingGraph,
  rootsOutsideClose: Collection<IrTypeKey>,
) {
  val hasParameters = node.lifecycleCallbacks.any { it.parameters.isNotEmpty() }
  if (!hasParameters) {
    return
  }
  val findBinding: (IrTypeKey) -> IrBinding? = { graph.findBinding(it) }
  // Only graphs with close callbacks need this walk, so it runs at most once and only on demand.
  val usedOutsideClose by lazy { keysReachableFrom(rootsOutsideClose, findBinding) }
  for (callback in node.lifecycleCallbacks) {
    for (parameter in callback.parameters) {
      val key = parameter.key
      val binding = resolveAliases(key.typeKey, findBinding) ?: continue
      val source = sequenceOf(parameter.source, node.sourceGraph)
      val parameterName = parameter.source.name.asString()
      val callbackName = "${callback.original.parentAsClass.name}.${callback.original.name}"

      // A direct request for an unscoped binding gets its own instance, which usually isn't the
      // one the rest of the graph uses. A multibinding is checked through its contributions.
      val freshBindings =
        if (key.wrappedType.isDeferrable()) {
          emptyList()
        } else {
          graph.freshInstanceBindings(binding, findBinding)
        }
      if (freshBindings.isNotEmpty()) {
        val callbackLabel = "${callback.annotationLabel} $callbackName"
        val isMultibinding = binding is IrBinding.Multibinding
        val diagnostic =
          MetroDiagnostic(
            id = MetroDiagnosticId.UNSCOPED_LIFECYCLE_PARAMETER,
            severity = MetroSeverity.WARNING,
            title =
              buildText {
                if (isMultibinding) {
                  append("Unscoped contributions to ")
                  append(key.typeKey.toText())
                  append(" are injected into parameter '$parameterName' of $callbackLabel. ")
                  append("These instances may be discarded after the callback.")
                } else {
                  append("Unscoped ")
                  append(key.typeKey.toText())
                  append(" is injected into parameter '$parameterName' of $callbackLabel. ")
                  append("This instance may be discarded after the callback.")
                }
              },
            sections =
              if (isMultibinding) {
                val items =
                  freshBindings
                    .map { it.renderContributionLocation() }
                    .sortedBy { it.location }
                    .map {
                      it.toLocatedItem(
                        preferSourceSnippet = true,
                        includeLeadingAnnotations = false,
                      )
                    }
                listOf(DiagnosticSection.Locations(header = null, items = items))
              } else {
                emptyList()
              },
            notes = listOf(Note.help(deferredRequestHelp(key.typeKey, graph))),
          )
        context.reportCompat(
          source,
          MetroDiagnostics.UNSCOPED_LIFECYCLE_PARAMETER,
          context.render(diagnostic).padForConsole(),
        )
      }

      // Graph inputs already exist, and children count as users of their parent's bindings.
      // Anything else that only close requests is created during close just to be cleaned up.
      val isGraphInput = binding is IrBinding.BoundInstance || binding is IrBinding.GraphDependency
      val isOwnedHere = !isGraphInput && !binding.isParentOwned
      val closeCanCreateIt = GraphLifecyclePhase.CLOSE in callback.phases && isOwnedHere
      if (closeCanCreateIt && binding.typeKey !in usedOutsideClose) {
        val graphName = node.sourceGraph.name.asString()
        val diagnostic =
          MetroDiagnostic(
            id = MetroDiagnosticId.CLOSE_ONLY_BINDING,
            severity = MetroSeverity.WARNING,
            title =
              buildText {
                append(key.typeKey.toText())
                append(" is reachable only from @OnGraphClose callbacks in $graphName. ")
                append("Resolving parameter '$parameterName' of $callbackName may create ")
                append(key.typeKey.toText())
                append(" for the first time during graph close.")
              },
          )
        context.reportCompat(
          source,
          MetroDiagnostics.CLOSE_ONLY_BINDING,
          context.render(diagnostic).padForConsole(),
        )
      }
    }
  }
}

/**
 * Returns the bindings that create a new instance for each request of [binding]. That's [binding]
 * itself, its unscoped contributions when it's a multibinding, or nothing when requests share one
 * instance. A binding read from a parent graph asks that graph.
 */
internal fun IrBindingGraph.freshInstanceBindings(
  binding: IrBinding,
  findBinding: (IrTypeKey) -> IrBinding?,
): List<IrBinding> {
  // Graph inputs, like @Provides parameters, are one instance whichever graph owns them.
  if (binding is IrBinding.BoundInstance) {
    return emptyList()
  }
  if (binding.isParentOwned) {
    return listOfNotNull(binding.takeIf { parentCreatesFreshInstances(it.typeKey) })
  }
  if (binding is IrBinding.Multibinding) {
    return binding.sourceBindings.flatMap { source ->
      val contribution = resolveAliases(source, findBinding) ?: return@flatMap emptyList()
      freshInstanceBindings(contribution, findBinding)
    }
  }
  return listOfNotNull(binding.takeIf { it.createsFreshInstances })
}

/** Follows `@Binds` aliases in this graph to the binding that creates the value. */
internal fun resolveAliases(key: IrTypeKey, findBinding: (IrTypeKey) -> IrBinding?): IrBinding? {
  var binding = findBinding(key) ?: return null
  while (binding is IrBinding.Alias && binding.aliasedType != binding.typeKey) {
    binding = findBinding(binding.aliasedType) ?: return null
  }
  return binding
}

/** True when each request for this binding can create a new object that a callback discards. */
private val IrBinding.createsFreshInstances: Boolean
  get() =
    when (this) {
      // An object is one instance, and an absent binding creates nothing.
      is IrBinding.ObjectClass,
      is IrBinding.Absent -> false
      // Factories and injectors hold no state of their own, so a new one per request is harmless.
      is IrBinding.AssistedFactory,
      is IrBinding.GraphExtensionFactory,
      is IrBinding.MembersInjected -> false
      // A multibinding's collection is cheap. Its contributions are checked on their own.
      is IrBinding.Multibinding -> false
      else -> !isScoped()
    }

/** Suggests the deferred forms that let a callback decide when to create [typeKey]. */
context(context: IrMetroContext)
private fun deferredRequestHelp(typeKey: IrTypeKey, graph: IrBindingGraph): String {
  val type = typeKey.render(short = true, includeQualifier = true)
  val isSuspend = graph.isTransitivelySuspend(typeKey)
  val functionProviders = context.options.enableFunctionProviders
  val (provider, lazy) =
    when {
      isSuspend && functionProviders -> "suspend () -> $type" to "SuspendLazy<$type>"
      isSuspend -> "SuspendProvider<$type>" to "SuspendLazy<$type>"
      functionProviders -> "() -> $type" to "Lazy<$type>"
      else -> "Provider<$type>" to "Lazy<$type>"
    }
  return "request $provider or $lazy to resolve $type explicitly"
}

private fun keysReachableFrom(
  roots: Collection<IrTypeKey>,
  findBinding: (IrTypeKey) -> IrBinding?,
): Set<IrTypeKey> {
  val reached = HashSet<IrTypeKey>()
  val pending = ArrayDeque(roots)
  while (pending.isNotEmpty()) {
    val key = pending.removeLast()
    if (!reached.add(key)) {
      continue
    }
    val binding = findBinding(key) ?: continue
    for (dependency in binding.dependencies) {
      pending.addLast(dependency.typeKey)
    }
  }
  return reached
}
