// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSection
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.factory
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.graph.GraphCreator
import dev.zacsweers.metro.compiler.graph.GraphLifecycleCallback
import dev.zacsweers.metro.compiler.graph.GraphLifecycleIssue
import dev.zacsweers.metro.compiler.graph.GraphLifecycleParameter
import dev.zacsweers.metro.compiler.graph.GraphLifecycleValidationResult
import dev.zacsweers.metro.compiler.graph.GraphLifecycleValidator
import dev.zacsweers.metro.compiler.graph.SuspendBindingPath
import dev.zacsweers.metro.compiler.graph.toTraceEntry
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.padForConsole
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName

internal typealias IrGraphLifecycleResult =
  GraphLifecycleValidationResult<IrTypeKey, IrContextualTypeKey, IrDeclaration>

/** IR metadata and diagnostic adapter for the shared [GraphLifecycleValidator]. */
internal class IrGraphLifecycleValidator(
  metroContext: IrMetroContext,
  private val node: GraphNode.Local,
  private val bindings: ScatterMap<IrTypeKey, IrBinding>,
  private val nonCallbackRootKeys: Collection<IrTypeKey>,
  private val suspendPath:
    (IrContextualTypeKey) -> SuspendBindingPath<IrTypeKey, IrContextualTypeKey>?,
  private val report: (MetroDiagnostic, IrElement) -> Unit,
) : IrMetroContext by metroContext {
  private val rootsBySourceParameter: Map<IrValueParameter, LifecycleCallbackParameter> =
    node.lifecycle.parameters.associateBy { it.sourceParameter }

  fun validate(): IrGraphLifecycleResult {
    val lifecycle = node.lifecycle
    val validation =
      GraphLifecycleValidator(
          graphName = node.lifecycleGraphName,
          closeKind = lifecycle.closeKind,
          callbacks = lifecycle.callbacks.map { it.toShared() },
          creator = creator(),
          bindings = bindings,
          suspendPath = suspendPath,
          nonCallbackRootKeys = nonCallbackRootKeys,
          delegateKey = { (it as? IrBinding.Alias)?.aliasedType },
          multibindingElements = { (it as? IrBinding.Multibinding)?.sourceBindings },
          isReused = ::isReused,
          isExternallySupplied = ::isExternallySupplied,
          functionProvidersEnabled = options.enableFunctionProviders,
        )
        .validate()
    for (issue in validation.issues) {
      report(issue.toDiagnostic(), anchorFor(issue))
    }
    return validation
  }

  private fun LifecycleCallback.toShared():
    GraphLifecycleCallback<IrContextualTypeKey, IrDeclaration> {
    return GraphLifecycleCallback(
      phase = phase,
      name = function.lifecycleCallbackName,
      isSuspend = function.ir.isSuspend,
      parameters =
        parameters.map { root ->
          GraphLifecycleParameter(
            name = root.parameter.name.asString(),
            contextKey = root.contextKey,
            source = root.sourceParameter,
          )
        },
      source = function.lifecycleCallbackDeclaration,
    )
  }

  /** Graph extensions are created by their parent, which checks how it creates them. */
  private fun creator(): GraphCreator<IrDeclaration>? {
    return when (node.sourceGraph.origin) {
      Origins.GeneratedGraphExtension -> null
      Origins.GeneratedDynamicGraph -> {
        val factoryImpl =
          node.sourceGraph.generatedDynamicGraphData?.factoryImpl ?: return GraphCreator.None
        val function = factoryImpl.superTypes.first().rawType().singleAbstractFunction()
        GraphCreator.Factory(function.name.asString(), function.isSuspend, function)
      }
      else -> {
        val factory = node.creator as? GraphNode.Creator.Factory ?: return GraphCreator.None
        val function = factory.function
        GraphCreator.Factory(function.name.asString(), function.isSuspend, function)
      }
    }
  }

  /** Bindings that hand every request the same value, or that don't create one at all. */
  private fun isReused(binding: IrBinding): Boolean {
    return when (binding) {
      is IrBinding.BoundInstance,
      is IrBinding.GraphDependency,
      is IrBinding.ObjectClass,
      is IrBinding.AssistedFactory,
      is IrBinding.GraphExtensionFactory,
      is IrBinding.MembersInjected,
      is IrBinding.Absent -> true
      else -> binding.isScoped()
    }
  }

  /** Bindings whose values this graph doesn't create. */
  private fun isExternallySupplied(binding: IrBinding): Boolean {
    return binding is IrBinding.BoundInstance ||
      binding is IrBinding.GraphDependency ||
      binding is IrBinding.ObjectClass
  }

  private fun GraphLifecycleIssue<IrTypeKey, IrContextualTypeKey, IrDeclaration>.toDiagnostic():
    MetroDiagnostic {
    return MetroDiagnostic(
      id = diagnosticId,
      severity = severity,
      title = textOf(title),
      sections = listOfNotNull(traceFor(this)),
      notes = notes,
    )
  }

  /** Traces a suspend reason from the callback parameter to the binding that suspends. */
  private fun traceFor(
    issue: GraphLifecycleIssue<IrTypeKey, IrContextualTypeKey, IrDeclaration>,
  ): DiagnosticSection.BindingTrace? {
    val path = issue.path ?: return null
    val parameter = issue.parameter?.source as? IrValueParameter ?: return null
    val root = rootsBySourceParameter[parameter] ?: return null
    val outermostFirst = buildList {
      add(IrBindingStack.Entry.lifecycleCallbackParameterAt(root))
      for (edge in path.edges) {
        val binding = bindings[edge.consumerKey] ?: break
        add(bindingStackEntryForDependency(binding, edge.dependency, edge.dependency.typeKey))
      }
      val source = bindings[path.sourceKey] as? IrBinding.Provided
      if (path.sourceIsSuspend && source != null) {
        add(
          IrBindingStack.Entry.providedAt(
            source.contextualTypeKey,
            source.providerFactory.function,
          ),
        )
      }
    }
    return DiagnosticSection.BindingTrace(
      graphName = node.sourceGraph.kotlinFqName.asString(),
      entries = outermostFirst.asReversed().map { it.toTraceEntry() },
    )
  }

  private fun anchorFor(
    issue: GraphLifecycleIssue<IrTypeKey, IrContextualTypeKey, IrDeclaration>,
  ): IrElement {
    val source = issue.source
    if (source != null && source.fileOrNull != null) {
      return source
    }
    return node.reportableSourceGraphDeclaration
  }
}

/** The user-facing name of a graph in lifecycle diagnostics. */
internal val GraphNode.lifecycleGraphName: String
  get() {
    val dynamicTarget =
      if (sourceGraph.origin == Origins.GeneratedDynamicGraph) {
        sourceGraph.superTypes.firstOrNull()?.rawType()
      } else {
        null
      }
    val graphClass = dynamicTarget ?: originalTypeKey.type.rawType()
    return graphClass.name.asString()
  }

/** Reports a lifecycle issue found outside a graph's own validation. */
internal fun IrMetroContext.reportGraphLifecycleIssue(
  issue: GraphLifecycleIssue<*, *, IrDeclaration>,
  fallback: IrDeclaration,
) {
  val diagnostic =
    MetroDiagnostic(
      id = issue.diagnosticId,
      severity = issue.severity,
      title = textOf(issue.title),
      notes = issue.notes,
    )
  val source = issue.source?.takeIf { it.fileOrNull != null } ?: fallback
  reportCompat(source, issue.diagnosticId.factory, render(diagnostic).padForConsole())
}
