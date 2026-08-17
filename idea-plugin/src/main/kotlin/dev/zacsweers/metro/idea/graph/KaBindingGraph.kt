// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import androidx.collection.ScatterMap
import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.diagnostics.SimilarBindingItem
import dev.zacsweers.metro.compiler.diagnostics.Style
import dev.zacsweers.metro.compiler.diagnostics.buildText
import dev.zacsweers.metro.compiler.diagnostics.invalidAssistedBindingDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.graph.AssistedBindingKind
import dev.zacsweers.metro.compiler.graph.BindingGraphValidator
import dev.zacsweers.metro.compiler.graph.DiagnosticRoutes
import dev.zacsweers.metro.compiler.graph.ErrorReporter
import dev.zacsweers.metro.compiler.graph.GraphAdjacency
import dev.zacsweers.metro.compiler.graph.GraphValidationIssue
import dev.zacsweers.metro.compiler.graph.MapContributionValidationMetadata
import dev.zacsweers.metro.compiler.graph.MissingBindingHints
import dev.zacsweers.metro.compiler.graph.MultibindingKind
import dev.zacsweers.metro.compiler.graph.MultibindingValidationMetadata
import dev.zacsweers.metro.compiler.graph.MutableBindingGraph
import dev.zacsweers.metro.compiler.graph.disambiguateIncompatibleScopes
import dev.zacsweers.metro.compiler.graph.duplicateMapKeysDiagnostic
import dev.zacsweers.metro.compiler.graph.emptyMultibindingDiagnostic
import dev.zacsweers.metro.compiler.graph.incompatibleScopeDiagnostic
import dev.zacsweers.metro.compiler.graph.putGraphRoot
import dev.zacsweers.metro.compiler.graph.toText
import dev.zacsweers.metro.compiler.graph.toTraceSection
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.model.canonicalContextKey
import dev.zacsweers.metro.idea.model.graphTypeKey
import org.jetbrains.kotlin.name.StandardClassIds

private typealias KaMutableBindingGraph =
  MutableBindingGraph<
    KaTypeSnapshot,
    KaTypeKey,
    KaContextualTypeKey,
    KaBinding,
    KaBindingStack.Entry,
    KaBindingStack,
  >

private typealias KaDiagnosticRoutes =
  DiagnosticRoutes<KaTypeSnapshot, KaTypeKey, KaContextualTypeKey, KaBindingStack.Entry>

/**
 * The Analysis API analog of the compiler's `IrBindingGraph`. Adapts one graph's index view to the
 * shared [MutableBindingGraph] and runs its validation via [seal]. Missing bindings, duplicates,
 * and cycles come from the shared core. One instance per seal.
 */
internal class KaBindingGraph(
  private val index: BindingIndex,
  queryContext: GraphQueryContext,
  private val options: MetroOptions,
) :
  // The TraceScope delegation satisfies seal()'s tracing context parameter with a no-op tracer
  TraceScope by TraceScope.noop(),
  ErrorReporter<KaBindingStack> {

  private val context = queryContext.graphContext
  private val graph = context.graph
  private val graphName = graph.classId?.asFqNameString() ?: graph.name ?: "<unknown>"
  private val graphConsumers = index.accessorsFor(graph)
  private val diagnostics = mutableListOf<KaGraphDiagnostic>()
  private var suspendKeys: Set<KaTypeKey> = emptySet()
  private val pendingEmptyMultibindings = mutableListOf<KaBinding.Multibinding>()

  // Cleared once sealing completes so lookup state doesn't outlive the population phase.
  private var _bindingLookup: KaBindingLookup? = KaBindingLookup(index, queryContext, options)
    set(value) {
      if (value == null) {
        field?.clear()
      }
      field = value
    }

  private val bindingLookup
    get() = _bindingLookup ?: error("Binding lookup already cleared")

  private val realGraph =
    KaMutableBindingGraph(
      newBindingStack = { KaBindingStack(graph) },
      newBindingStackEntry = { contextKey, callingBinding, roots ->
        // A null calling binding means the key was requested directly by a root
        if (callingBinding == null) {
          roots.getValue(contextKey)
        } else {
          KaBindingStack.Entry.injectedAt(contextKey, callingBinding)
        }
      },
      computeBindings = { contextKey, _, stack ->
        bindingLookup.lookup(contextKey) { key, bindings ->
          reportDuplicateBindings(key, bindings, stack)
        }
      },
      errorReporter = this,
      missingBindingDiagnosticDetails = ::missingBindingDiagnosticDetails,
    )

  fun seal(): KaGraphValidationResult.Completed {
    val setupStack = KaBindingStack(graph)
    val keeps = LinkedHashMap<KaContextualTypeKey, KaBindingStack.Entry>()
    val extensions = index.extensionsOf(graph)
    if (extensions.isNotEmpty()) {
      // Extension children depend on this graph's instance and reserve its key in the compiler's
      // parent seal. Keep it explicitly so the instance joins the sorted root set and the
      // validated order matches the compiler (explicit keys win ties over discovered ones).
      graph.graphTypeKey()?.let { selfKey ->
        val selfContextKey = selfKey.canonicalContextKey()
        keeps[selfContextKey] =
          KaBindingStack.Entry(
            contextKey = selfContextKey,
            pointer = graph.pointer,
            isSynthetic = true,
          )
      }
    }
    for (extension in extensions) {
      val binding = graphExtensionBinding(extension) ?: continue
      realGraph.tryPut(binding, setupStack)
      keeps[binding.contextualTypeKey] =
        KaBindingStack.Entry(
          contextKey = binding.contextualTypeKey,
          pointer = binding.pointer,
          isSynthetic = true,
        )
    }

    val roots = LinkedHashMap<KaContextualTypeKey, KaBindingStack.Entry>()
    for (consumer in graphConsumers) {
      // hasDefault is what makes the shared core treat an absent optional binding as not missing
      val contextKey = consumer.contextKey.withDefault(consumer.isOptional)
      roots.putGraphRoot(
        contextKey,
        KaBindingStack.Entry.requestedAt(contextKey, consumer, graphName),
      )
    }

    val topology =
      try {
        val topo =
          realGraph.seal(
            roots = roots,
            keep = keeps,
            shrinkUnusedBindings = options.shrinkUnusedBindings,
            validateBindings = ::validateBindings,
          )
        reportEmptyMultibindings()
        topo
      } catch (_: SealAborted) {
        null
      } finally {
        // Clear out the binding lookup now that we're done
        _bindingLookup = null
      }

    // The seal's ScatterMap is handed off directly. The graph adapter is discarded after seal,
    // so nothing else can mutate it.
    return KaGraphValidationResult.Completed(
      context,
      diagnostics.toList(),
      topology,
      realGraph.bindings,
      suspendKeys,
    )
  }

  // The bindings the in-flight report is about, attached to the next reported diagnostic. The
  // shared core builds the diagnostic itself, so this is the only seam to carry them through.
  private var pendingRelated: List<KaBinding> = emptyList()

  override fun report(diagnostic: MetroDiagnostic, stack: KaBindingStack) {
    diagnostics += KaGraphDiagnostic(diagnostic, stack.entries.toList(), pendingRelated)
  }

  override fun reportFatal(diagnostic: MetroDiagnostic, stack: KaBindingStack): Nothing {
    report(diagnostic, stack)
    throw SealAborted()
  }

  override fun flush() {}

  private fun reportDuplicateBindings(
    key: KaTypeKey,
    bindings: List<KaBinding>,
    stack: KaBindingStack,
  ) {
    pendingRelated = bindings
    try {
      realGraph.reportDuplicateBindings(key, bindings, stack)
    } finally {
      pendingRelated = emptyList()
    }
  }

  private fun reportEmptyMultibindings() {
    for (multibinding in pendingEmptyMultibindings) {
      report(emptyMultibindingDiagnostic(multibinding.typeKey), KaBindingStack(graph))
    }
  }

  private fun validateBindings(
    bindings: ScatterMap<KaTypeKey, KaBinding>,
    stack: KaBindingStack,
    roots: Map<KaContextualTypeKey, KaBindingStack.Entry>,
    adjacency: GraphAdjacency<KaTypeKey>,
  ) {
    val rootsByTypeKey = roots.mapKeys { it.key.typeKey }
    val diagnosticRoutes = DiagnosticRoutes(roots, adjacency.forward)
    val structuralValidator =
      BindingGraphValidator(
        bindings = bindings,
        graphScopes = context.scopingAnnotations,
        scopeOf = { it.scope },
        assistedKindOf = ::assistedKindOf,
        multibindingOf = ::multibindingMetadataOf,
        mapContributionOf = { MapContributionValidationMetadata(it.mapKeyValue) },
        rootKeys = rootsByTypeKey.keys,
        reverseAdjacency = adjacency.reverse,
      )
    bindings.forEachValue { binding ->
      ProgressManager.checkCanceled()
      structuralValidator.validate(binding) { issue ->
        reportStructuralIssue(issue, stack, diagnosticRoutes, rootsByTypeKey)
      }
    }
    suspendKeys =
      KaSuspendBindingValidator(
          graph = graph,
          graphName = graphName,
          options = options,
          graphConsumers = graphConsumers,
          bindings = bindings,
          runtimeCoroutinesAvailable = graph.runtimeCoroutinesAvailable,
          report = ::reportSuspendDiagnostic,
        )
        .validate()
  }

  private fun reportStructuralIssue(
    issue: GraphValidationIssue<KaBinding, KaAnnotationSnapshot, String>,
    stack: KaBindingStack,
    diagnosticRoutes: KaDiagnosticRoutes,
    roots: Map<KaTypeKey, KaBindingStack.Entry>,
  ) {
    when (issue) {
      is GraphValidationIssue.IncompatibleScope ->
        reportIncompatibleScope(issue.binding, issue.bindingScope, stack, diagnosticRoutes)
      is GraphValidationIssue.EmptyMultibinding ->
        pendingEmptyMultibindings +=
          checkNotNull(issue.binding as? KaBinding.Multibinding) {
            "Only multibindings can produce empty multibinding issues"
          }
      is GraphValidationIssue.DuplicateMapKey ->
        reportDuplicateMapKey(
          checkNotNull(issue.multibinding as? KaBinding.Multibinding) {
            "Only multibindings can produce duplicate map key issues"
          },
          issue.mapKey,
          issue.contributions,
          stack,
          diagnosticRoutes,
        )
      is GraphValidationIssue.InvalidAssistedInjection ->
        reportInvalidAssistedInjection(
          checkNotNull(issue.binding as? KaBinding.ConstructorInjected) {
            "Only assisted constructor bindings can produce invalid assisted injection issues"
          },
          issue.requestingBinding,
          stack,
          diagnosticRoutes,
          roots,
        )
    }
  }

  private fun assistedKindOf(binding: KaBinding): AssistedBindingKind? =
    when {
      binding is KaBinding.ConstructorInjected && binding.isAssisted -> AssistedBindingKind.TARGET
      binding is KaBinding.AssistedFactory -> AssistedBindingKind.FACTORY
      else -> null
    }

  private fun multibindingMetadataOf(
    binding: KaBinding
  ): MultibindingValidationMetadata<KaTypeKey>? =
    (binding as? KaBinding.Multibinding)?.let {
      MultibindingValidationMetadata(
        kind =
          if (it.typeKey.type.classId == StandardClassIds.Map) {
            MultibindingKind.MAP
          } else {
            MultibindingKind.SET
          },
        allowEmpty = it.allowEmpty,
        sourceBindings = it.sourceBindings,
      )
    }

  private fun reportSuspendDiagnostic(
    diagnostic: MetroDiagnostic,
    stack: KaBindingStack,
    related: List<KaBinding>,
  ) {
    pendingRelated = related
    try {
      report(diagnostic, stack)
    } finally {
      pendingRelated = emptyList()
    }
  }

  private fun reportDuplicateMapKey(
    binding: KaBinding.Multibinding,
    mapKey: String?,
    contributions: List<KaBinding>,
    stack: KaBindingStack,
    diagnosticRoutes: KaDiagnosticRoutes,
  ) {
    checkNotNull(mapKey) { "Map key should not be null for map multibindings" }

    val diagnosticStack = buildStackToRoot(binding.typeKey, diagnosticRoutes, stack)
    val locationDiagnostics = contributions.map { it.renderLocationDiagnostic(short = true) }
    val locations = locationDiagnostics.map { it.toLocatedItem() }
    pendingRelated = contributions
    try {
      report(
        duplicateMapKeysDiagnostic(
          typeKey = binding.typeKey,
          mapKeyRender = mapKey,
          locations = locations,
          trace = diagnosticStack.toTraceSection(),
          extraNotes = locationDiagnostics.flatMap { it.notes }.distinct(),
        ),
        diagnosticStack,
      )
    } finally {
      pendingRelated = emptyList()
    }
  }

  private fun reportIncompatibleScope(
    binding: KaBinding,
    bindingScope: KaAnnotationSnapshot,
    stack: KaBindingStack,
    diagnosticRoutes: KaDiagnosticRoutes,
  ) {
    val renders =
      disambiguateIncompatibleScopes(
        bindingScope = bindingScope,
        graphScopes = context.scopingAnnotations,
        shortRender = { it.render(short = true) },
        fullRender = { it.render(short = false) },
      )
    val diagnosticStack = buildStackToRoot(binding.typeKey, diagnosticRoutes, stack)
    diagnosticStack.push(
      KaBindingStack.Entry(
        contextKey = binding.contextualTypeKey,
        usage = "(scoped to '${renders.bindingScope}')",
        pointer = binding.pointer,
      )
    )
    report(
      incompatibleScopeDiagnostic(
        graphName = graphName,
        renders = renders,
        trace = diagnosticStack.toTraceSection(),
      ),
      diagnosticStack,
    )
  }

  private fun reportInvalidAssistedInjection(
    binding: KaBinding.ConstructorInjected,
    requestingBinding: KaBinding?,
    stack: KaBindingStack,
    diagnosticRoutes: KaDiagnosticRoutes,
    roots: Map<KaTypeKey, KaBindingStack.Entry>,
  ) {
    val diagnosticStack = buildStackToRoot(binding.typeKey, diagnosticRoutes, stack)
    val injectionSite =
      requestingBinding?.typeKey?.toText()
        ?: roots[binding.typeKey]?.graphContext?.let { textOf(it, Style.EMPHASIS) }
    val assistedFactory = index.assistedFactoriesForTarget(binding.typeKey).firstOrNull()
    val related = listOfNotNull(binding, requestingBinding, assistedFactory)
    pendingRelated = related
    try {
      report(
        invalidAssistedBindingDiagnostic(
          assistedType = binding.typeKey.toText(),
          injectionSite = injectionSite,
          assistedFactory = assistedFactory?.typeKey?.toText(),
        ),
        diagnosticStack,
      )
    } finally {
      pendingRelated = emptyList()
    }
  }

  private fun buildStackToRoot(
    key: KaTypeKey,
    diagnosticRoutes: KaDiagnosticRoutes,
    fallback: KaBindingStack,
  ): KaBindingStack {
    val route =
      diagnosticRoutes.routeToRoot(key) { callingKey, dependencyKey ->
        val callingBinding = checkNotNull(realGraph.bindings[callingKey])
        val contextKey =
          callingBinding.dependencies.first { dependency ->
            dependency.typeKey == dependencyKey
          }
        KaBindingStack.Entry.injectedAt(contextKey, callingBinding)
      }
    if (route.isEmpty()) return fallback.copy()
    val result = KaBindingStack(graph)
    for (entry in route) {
      result.push(entry)
    }
    return result
  }

  private fun missingBindingDiagnosticDetails(typeKey: KaTypeKey): MissingBindingHints {
    val notes = mutableListOf<Note>()
    val similar = mutableListOf<SimilarBindingItem>()

    for (binding in index.bindingsWithType(typeKey)) {
      when {
        binding.typeKey == typeKey -> {
          // A binding for this exact key exists but is not a member of this graph.
          notes +=
            Note.note(
              buildText {
                append("a binding for this key exists at ")
                append(binding.location() ?: "<unknown>")
                append(" but is not a member of this graph. Check its scope, its container's ")
                append("wiring, or its contribution scope.")
              }
            )
        }
        binding.typeKey.type == typeKey.type && binding.typeKey.qualifier != typeKey.qualifier -> {
          similar +=
            SimilarBindingItem(
              key = binding.typeKey.toText(),
              description = "same type, different qualifier",
              location = binding.location(),
            )
        }
      }
    }

    return MissingBindingHints(notes = notes, similarBindings = similar)
  }

  private fun graphExtensionBinding(extension: KaGraphDeclaration): KaBinding.GraphExtension? {
    val extensionKey = graphTypeKey(extension) ?: return null
    val ownerKey = graphTypeKey(graph) ?: return null
    return KaBinding.GraphExtension(extension.pointer, extensionKey, ownerKey)
  }

  private fun graphTypeKey(graph: KaGraphDeclaration): KaTypeKey? = graph.graphTypeKey()
}

/** Thrown by [KaBindingGraph.reportFatal] and caught by [KaBindingGraph.seal]. */
private class SealAborted : RuntimeException() {
  // Control flow only, so skip the expensive stack trace capture
  override fun fillInStackTrace(): Throwable = this
}
