// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import androidx.collection.ScatterMap
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.graph.GraphCloseKind
import dev.zacsweers.metro.compiler.graph.GraphCreator
import dev.zacsweers.metro.compiler.graph.GraphExtensionCreationSite
import dev.zacsweers.metro.compiler.graph.GraphLifecycleCallback
import dev.zacsweers.metro.compiler.graph.GraphLifecycleIssue
import dev.zacsweers.metro.compiler.graph.GraphLifecycleParameter
import dev.zacsweers.metro.compiler.graph.GraphLifecycleValidationResult
import dev.zacsweers.metro.compiler.graph.GraphLifecycleValidator
import dev.zacsweers.metro.compiler.graph.SuspendBindingPath
import dev.zacsweers.metro.compiler.graph.toTraceSection
import dev.zacsweers.metro.compiler.graph.validateGraphExtensionCreation
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.GraphComposition
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey

/** Where a lifecycle diagnostic is reported in source. */
internal typealias KaLifecycleSource = SmartPsiElementPointer<out PsiElement>

internal typealias KaGraphLifecycleResult =
  GraphLifecycleValidationResult<KaTypeKey, KaContextualTypeKey, KaLifecycleSource>

/** What a parent graph needs to check how it creates one of its graph extensions. */
internal class KaGraphLifecycleSummary(
  val graph: KaGraphDeclaration,
  val closeKind: GraphCloseKind?,
  /** Why the extension's init callbacks suspend, or null when they don't. */
  val initSuspendReason: String?,
)

/**
 * Analysis API metadata and diagnostic adapter for the shared [GraphLifecycleValidator]. It mirrors
 * the compiler's `IrGraphLifecycleValidator`.
 */
internal class KaGraphLifecycleValidator(
  private val context: GraphContext,
  composition: GraphComposition,
  graphConsumers: List<ConsumerEntry>,
  private val options: MetroOptions,
  private val sources: ValidationSourceSnapshot,
  private val report: (MetroDiagnostic, KaBindingStack, KaLifecycleSource?) -> Unit,
) {
  private val graph = context.graph
  private val graphName = graph.lifecycleGraphName
  private val creationSites = composition.extensionCreationSites
  private val consumersByParameter = HashMap<KaLifecycleSource, ConsumerEntry>()
  private val callbacks: List<GraphLifecycleCallback<KaContextualTypeKey, KaLifecycleSource>>

  /** Null when the graph doesn't implement `CloseableGraph` or `SuspendCloseableGraph`. */
  val closeKind: GraphCloseKind? = graphCloseKind(composition.supertypeKeys)

  init {
    val parametersByCallback = HashMap<Any, MutableList<ConsumerEntry>>()
    for (consumer in graphConsumers) {
      val callback = consumer.lifecycleCallback ?: continue
      parametersByCallback.getOrPut(callback.sourceIdentity ?: callback, ::mutableListOf) +=
        consumer
      consumersByParameter[consumer.pointer] = consumer
    }
    callbacks =
      composition.lifecycleCallbacks.map { callback ->
        val parameters = parametersByCallback[callback.sourceIdentity ?: callback].orEmpty()
        GraphLifecycleCallback(
          phase = callback.phase,
          name = callback.name,
          isSuspend = callback.isSuspend,
          parameters =
            parameters.map { consumer ->
              GraphLifecycleParameter(
                name = sources.name(consumer).orEmpty(),
                contextKey = consumer.contextKey,
                source = consumer.pointer,
              )
            },
          source = callback.pointer,
        )
      }
  }

  /** Whether anything here needs suspend provider support. */
  fun usesSuspend(): Boolean {
    if (closeKind == GraphCloseKind.SUSPEND) return true
    return callbacks.any { callback ->
      val requestsSuspendWrapper =
        callback.parameters.any { it.contextKey.wrappedType.containsSuspendWrapper() }
      callback.isSuspend || requestsSuspendWrapper
    }
  }

  fun validate(
    bindings: ScatterMap<KaTypeKey, KaBinding>,
    nonCallbackRootKeys: Collection<KaTypeKey>,
    suspendPath: (KaContextualTypeKey) -> SuspendBindingPath<KaTypeKey, KaContextualTypeKey>?,
  ): KaGraphLifecycleResult? {
    if (callbacks.isEmpty() && closeKind == null) return null
    val validation =
      GraphLifecycleValidator(
          graphName = graphName,
          closeKind = closeKind,
          callbacks = callbacks,
          creator = creator(),
          bindings = bindings,
          suspendPath = suspendPath,
          nonCallbackRootKeys = nonCallbackRootKeys,
          delegateKey = { (it as? KaBinding.Alias)?.consumedKey?.typeKey },
          multibindingElements = { (it as? KaBinding.Multibinding)?.sourceBindings },
          isReused = ::isReused,
          isExternallySupplied = ::isExternallySupplied,
          functionProvidersEnabled = options.enableFunctionProviders,
          checkCanceled = ProgressManager::checkCanceled,
        )
        .validate()
    for (issue in validation.issues) {
      reportIssue(issue, bindings)
    }
    return validation
  }

  /** Checks how this graph creates each extension that sealed without errors. */
  fun validateExtensionCreation(extensions: List<KaGraphLifecycleSummary>) {
    for (extension in extensions) {
      ProgressManager.checkCanceled()
      val sites =
        creationSites
          .filter { it.extension in extension.graph.selfReferences }
          .map { site ->
            GraphExtensionCreationSite<KaLifecycleSource>(
              name = site.name,
              isFactory = site.isFactory,
              isSuspend = site.isSuspend,
              source = site.pointer,
            )
          }
      val issues =
        validateGraphExtensionCreation<KaTypeKey, KaContextualTypeKey, KaLifecycleSource>(
          parentName = graphName,
          parentCloseKind = closeKind,
          extensionName = extension.graph.lifecycleGraphName,
          extensionCloseKind = extension.closeKind,
          extensionInitSuspendReason = extension.initSuspendReason,
          sites = sites,
        )
      for (issue in issues) {
        reportIssue(issue, bindings = null)
      }
    }
  }

  /** Graph extensions are created by their parent, which checks how it creates them. */
  private fun creator(): GraphCreator<KaLifecycleSource>? {
    if (graph.isExtension) return null
    val dynamicGraph = context.dynamicGraph
    val usesFactory = dynamicGraph?.isFactory ?: true
    val function = graph.factoryFunction
    if (!usesFactory || function == null) return GraphCreator.None
    return GraphCreator.Factory(function.name, function.isSuspend, function.pointer)
  }

  /** Bindings that hand every request the same value, or that don't create one at all. */
  private fun isReused(binding: KaBinding): Boolean {
    return when (binding) {
      is KaBinding.BoundInstance,
      is KaBinding.GraphDependency,
      is KaBinding.GraphInstance,
      is KaBinding.AssistedFactory -> true
      is KaBinding.ConstructorInjected -> binding.isObject || binding.scope != null
      is KaBinding.GraphExtension -> binding.isFactory
      else -> binding.scope != null
    }
  }

  /** Bindings whose values this graph doesn't create. */
  private fun isExternallySupplied(binding: KaBinding): Boolean {
    return when (binding) {
      is KaBinding.BoundInstance,
      is KaBinding.GraphDependency,
      is KaBinding.GraphInstance -> true
      is KaBinding.ConstructorInjected -> binding.isObject
      else -> false
    }
  }

  private fun reportIssue(
    issue: GraphLifecycleIssue<KaTypeKey, KaContextualTypeKey, KaLifecycleSource>,
    bindings: ScatterMap<KaTypeKey, KaBinding>?,
  ) {
    val stack = issueStack(issue, bindings)
    val trace = if (issue.path == null) null else stack.toTraceSection()
    val diagnostic =
      MetroDiagnostic(
        id = issue.diagnosticId,
        severity = issue.severity,
        title = textOf(issue.title),
        sections = listOfNotNull(trace),
        notes = issue.notes,
      )
    report(diagnostic, stack, issue.source)
  }

  /** Traces a callback parameter, and a suspend reason's path when there is one. */
  private fun issueStack(
    issue: GraphLifecycleIssue<KaTypeKey, KaContextualTypeKey, KaLifecycleSource>,
    bindings: ScatterMap<KaTypeKey, KaBinding>?,
  ): KaBindingStack {
    val stack = KaBindingStack(graph)
    val parameter = issue.parameter ?: return stack
    val consumer = consumersByParameter[parameter.source] ?: return stack
    stack.push(
      KaBindingStack.Entry.requestedAt(
        parameter.contextKey,
        consumer,
        graphName,
        parameter.name,
      ),
    )
    val path = issue.path ?: return stack
    if (bindings == null) return stack
    for (edge in path.edges) {
      val binding = bindings[edge.consumerKey] ?: break
      stack.push(
        KaBindingStack.Entry.injectedAt(edge.dependency, binding, sources.location(binding)),
      )
    }
    if (path.sourceIsSuspend) {
      bindings[path.sourceKey]?.let {
        stack.push(KaBindingStack.Entry.providedAt(it, sources.location(it)))
      }
    }
    return stack
  }
}

/** The graph's simple name, which lifecycle diagnostics use. */
internal val KaGraphDeclaration.lifecycleGraphName: String
  get() = classId?.shortClassName?.asString() ?: name ?: "<unknown>"

/** How a graph closes, based on which closeable graph interface it implements. */
private fun graphCloseKind(supertypeKeys: Set<KaTypeKey>): GraphCloseKind? {
  var closeKind: GraphCloseKind? = null
  for (key in supertypeKeys) {
    when (key.type.classId) {
      MetroClassIds.suspendCloseableGraph -> return GraphCloseKind.SUSPEND
      MetroClassIds.closeableGraph -> closeKind = GraphCloseKind.SYNC
    }
  }
  return closeKind
}
