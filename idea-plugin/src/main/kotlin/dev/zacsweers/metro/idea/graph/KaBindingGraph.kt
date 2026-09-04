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
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
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
import dev.zacsweers.metro.compiler.graph.MissingBindingHints
import dev.zacsweers.metro.compiler.graph.MultibindingKind
import dev.zacsweers.metro.compiler.graph.MutableBindingGraph
import dev.zacsweers.metro.compiler.graph.disambiguateIncompatibleScopes
import dev.zacsweers.metro.compiler.graph.duplicateMapKeysDiagnostic
import dev.zacsweers.metro.compiler.graph.emptyMultibindingDiagnostic
import dev.zacsweers.metro.compiler.graph.incompatibleScopeDiagnostic
import dev.zacsweers.metro.compiler.graph.putGraphRoot
import dev.zacsweers.metro.compiler.graph.toText
import dev.zacsweers.metro.compiler.graph.toTraceSection
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.idea.model.BindingResolutionSession
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
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

private typealias KaSourcePointer = SmartPsiElementPointer<out PsiElement>

/**
 * The Analysis API analog of the compiler's `IrBindingGraph`. Adapts one graph's index view to the
 * shared [MutableBindingGraph] and runs its validation via [seal]. Missing bindings, duplicates,
 * and cycles come from the shared core. One instance per seal. The session supplies the immutable
 * index for every query in that seal.
 */
internal class KaBindingGraph(
  private val session: BindingResolutionSession,
  private val queryContext: GraphQueryContext,
  private val options: MetroOptions,
  private val sources: ValidationSourceSnapshot,
  /** Keys extension children delegate to this graph, validated here like the compiler does. */
  private val reservations: List<ReservedParentKey> = emptyList(),
  private val resolveParentGraph: (GraphContext) -> ParentGraphLookup? = { null },
) :
  // The TraceScope delegation satisfies seal()'s tracing context parameter with a no-op tracer
  TraceScope by TraceScope.noop(),
  ErrorReporter<KaBindingStack> {

  private val index = session.index
  private val context = queryContext.graphContext
  private val graph = context.graph
  private val graphName = graph.classId?.asFqNameString() ?: graph.name ?: "<unknown>"
  private val graphConsumers = session.accessorsFor(queryContext)
  private val diagnostics = mutableListOf<KaGraphDiagnostic>()
  private var suspendKeys: Set<KaTypeKey> = emptySet()
  private val pendingEmptyMultibindings = mutableListOf<KaBinding.Multibinding>()
  private var reportedLazyFactorySites: MutableSet<LazyFactorySite>? = null
  private val lazyFactoryTypes by
    lazy(LazyThreadSafetyMode.NONE) {
      HashMap<KaTypeSnapshot, KaBinding.AssistedFactory?>()
    }

  // Cleared once sealing completes so lookup state doesn't outlive the population phase.
  private var _bindingLookup: KaBindingLookup? =
    KaBindingLookup(session, queryContext, options, resolveParentGraph)
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
          KaBindingStack.Entry.injectedAt(
            contextKey,
            callingBinding,
            sources.location(callingBinding),
          )
        }
      },
      computeBindings = { contextKey, _, stack ->
        val resolved =
          bindingLookup.lookup(contextKey) { key, bindings ->
            reportDuplicateBindings(key, bindings, stack)
          }
        for (binding in resolved) {
          ProgressManager.checkCanceled()
          // Check limits after selection so an explicit provider can terminate a growing chain.
          if (binding is KaBinding.AssistedFactory || binding is KaBinding.ConstructorInjected) {
            val incompleteReason = index.incompleteClassBindingReason(binding, queryContext)
            if (incompleteReason != null) throw IncompleteGraphAnalysis(incompleteReason)
          }
          validateLazyAssistedDependencies(binding, stack)
        }
        resolved
      },
      errorReporter = this,
      missingBindingDiagnosticDetails = ::missingBindingDiagnosticDetails,
      findSuspendCycleKey = ::findSuspendCycleKey,
      bindingLocationDiagnostic = sources::locationDiagnostic,
    )

  private fun findSuspendCycleKey(
    cycleKeys: List<KaTypeKey>,
    bindings: ScatterMap<KaTypeKey, KaBinding>,
  ): KaTypeKey? {
    if (!options.enableSuspendProviders) {
      return null
    }
    val suspendKeys = SuspendBindingAnalysis(bindings::get).analyze(cycleKeys)
    return cycleKeys.firstOrNull { it in suspendKeys }
  }

  fun seal(): KaGraphValidationResult.Completed {
    val keeps = LinkedHashMap<KaContextualTypeKey, KaBindingStack.Entry>()
    val extensions = session.extensionsOf(queryContext)
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
    for (binding in bindingLookup.directExtensionBindings()) {
      ProgressManager.checkCanceled()
      // Resolve the kept key through the normal lookup, where an explicit binding or an existing
      // graph-supertype alias wins over synthesizing a child instance.
      keeps[binding.contextualTypeKey] =
        KaBindingStack.Entry(
          contextKey = binding.contextualTypeKey,
          pointer = binding.pointer,
          isSynthetic = true,
        )
    }
    for (reservation in reservations) {
      ProgressManager.checkCanceled()
      // The parent may not request this collection itself, so its synthetic element exists only
      // because a child requested it. Carry the original element into the parent's lookup.
      if (reservation.key.qualifier?.classId == MetroClassIds.multibindingElement) {
        bindingLookup.registerReservedBinding(reservation.binding)
      }
      val contextKey = reservation.key.canonicalContextKey()
      keeps.putIfAbsent(
        contextKey,
        KaBindingStack.Entry(
          contextKey = contextKey,
          pointer = reservation.pointer,
          isSynthetic = true,
        ),
      )
    }

    val roots = LinkedHashMap<KaContextualTypeKey, KaBindingStack.Entry>()
    for (consumer in graphConsumers) {
      ProgressManager.checkCanceled()
      // hasDefault is what makes the shared core treat an absent optional binding as not missing.
      // Union with any default already on the key so a defaulted context key never turns required.
      val contextKey =
        consumer.contextKey.withDefault(consumer.isOptional || consumer.contextKey.hasDefault)
      roots.putGraphRoot(
        contextKey,
        KaBindingStack.Entry.requestedAt(contextKey, consumer, graphName, sources.name(consumer)),
      )
    }

    // FIR checks graph request sites independently, before missing-binding resolution. Keep each
    // accessor even when several request the same canonical key.
    for (consumer in graphConsumers) {
      ProgressManager.checkCanceled()
      if (consumer.graphRequestKind == null) continue
      val contextKey = consumer.contextKey
      if (!contextKey.isWrappedInLazy) continue
      val factory = assistedFactoryFor(contextKey) ?: continue
      val diagnosticStack = KaBindingStack(graph)
      diagnosticStack.push(
        KaBindingStack.Entry.requestedAt(contextKey, consumer, graphName, sources.name(consumer))
      )
      val sourcePointer = consumer.injectedMemberPointer ?: consumer.pointer
      reportLazyAssistedFactory(factory, contextKey, null, diagnosticStack, sourcePointer)
    }

    var reservedParentBindings: Map<KaTypeKey, KaBinding> = emptyMap()
    var sealCompleted = false
    val topology =
      try {
        val topo =
          realGraph.seal(
            roots = roots,
            keep = keeps,
            shrinkUnusedBindings = options.shrinkUnusedBindings,
            ensureActive = ProgressManager::checkCanceled,
            validateBindings = ::validateBindings,
          )
        // The compiler stops before empty-multibinding reporting when the seal produced errors.
        if (diagnostics.none { it.severity == MetroSeverity.ERROR }) {
          reportEmptyMultibindings()
        }
        sealCompleted = true
        topo
      } catch (_: SealAborted) {
        sealCompleted = true
        null
      } finally {
        // Skip copying parent reservations when cancellation interrupts the seal.
        if (sealCompleted) {
          reservedParentBindings = _bindingLookup?.reservedParentBindings?.toMap().orEmpty()
        }
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
      reservedParentBindings,
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
      ProgressManager.checkCanceled()
      pendingRelated = listOf(multibinding)
      try {
        report(emptyMultibindingDiagnostic(multibinding.typeKey), KaBindingStack(graph))
      } finally {
        pendingRelated = emptyList()
      }
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
        // The graph's own scopes, not the merged chain. Ancestor-scoped bindings either delegate
        // to their owning graph or, when declared by this graph itself, must be flagged here the
        // way the compiler excludes extended-parent scopes from its node scopes.
        graphScopes = graph.scopingAnnotations,
        scopeOf = { it.scope },
        assistedKindOf = ::assistedKindOf,
        multibindingKindOf = { binding ->
          if (binding !is KaBinding.Multibinding) {
            null
          } else if (binding.typeKey.type.classId == StandardClassIds.Map) {
            MultibindingKind.MAP
          } else {
            MultibindingKind.SET
          }
        },
        multibindingAllowsEmpty = { (it as KaBinding.Multibinding).allowEmpty },
        multibindingSourceKeys = { (it as KaBinding.Multibinding).sourceBindings },
        isMapContribution = { it.multibindingId != null },
        mapKeyOf = { it.mapKeyValue },
        rootKeys = rootsByTypeKey.keys,
        reverseAdjacency = adjacency.reverse,
      )
    bindings.forEachValue { binding ->
      ProgressManager.checkCanceled()
      structuralValidator.validate(binding, ProgressManager::checkCanceled) { issue ->
        reportStructuralIssue(issue, stack, diagnosticRoutes, rootsByTypeKey)
      }
    }
    suspendKeys =
      KaSuspendBindingValidator(
          graph = graph,
          graphName = graphName,
          options = options,
          sources = sources,
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
    val locationDiagnostics = contributions.map(sources::locationDiagnostic)
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
        graphScopes = graph.scopingAnnotations,
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

  /** Inspect original assisted-target dependencies before their synthetic Provider wrapping. */
  private fun validateLazyAssistedDependencies(
    requestingBinding: KaBinding,
    stack: KaBindingStack,
  ) {
    if (requestingBinding is KaBinding.AssistedFactory) {
      validateLazyAssistedDependencies(
        requestingBinding,
        requestingBinding.targetConstructorDependencies,
        stack,
      )
      validateLazyAssistedDependencies(
        requestingBinding,
        requestingBinding.targetMemberDependencies,
        stack,
      )
      return
    }
    validateLazyAssistedDependencies(requestingBinding, requestingBinding.dependencies, stack)
  }

  private fun validateLazyAssistedDependencies(
    requestingBinding: KaBinding,
    dependencies: List<KaContextualTypeKey>,
    stack: KaBindingStack,
  ) {
    var checkedRequests: MutableSet<KaContextualTypeKey>? = null
    for (dependency in dependencies) {
      ProgressManager.checkCanceled()
      if (!dependency.isWrappedInLazy) continue
      val factory = assistedFactoryFor(dependency) ?: continue
      val checked = checkedRequests ?: HashSet<KaContextualTypeKey>().also { checkedRequests = it }
      if (!checked.add(dependency)) continue
      val sourcePointers = lazyRequestSources(dependency, requestingBinding)
      for (sourcePointer in sourcePointers) {
        ProgressManager.checkCanceled()
        val diagnosticStack = stack.copy()
        diagnosticStack.push(
          KaBindingStack.Entry.injectedAt(
            dependency,
            requestingBinding,
            sources.location(requestingBinding),
          )
        )
        reportLazyAssistedFactory(
          factory,
          dependency,
          requestingBinding,
          diagnosticStack,
          sourcePointer,
        )
      }
    }
  }

  /** Maps a copied graph edge back to its distinct constructor, provider, or member sites. */
  private fun lazyRequestSources(
    request: KaContextualTypeKey,
    requestingBinding: KaBinding,
  ): List<KaSourcePointer> {
    val requestSources =
      sources.lazyRequestSources(request) ?: return listOf(requestingBinding.pointer)
    val result = LinkedHashMap<Any, KaSourcePointer>()

    fun addAll(pointers: List<KaSourcePointer>?) {
      for (pointer in pointers.orEmpty()) {
        val identity = index.pointerIdentity(pointer) ?: pointer
        result.putIfAbsent(identity, pointer)
      }
    }

    val ownerIdentity = index.pointerIdentity(requestingBinding.pointer)
    if (ownerIdentity != null) addAll(requestSources.byDeclaration[ownerIdentity])
    val targetClassId =
      if (requestingBinding is KaBinding.AssistedFactory) {
        requestingBinding.targetTypeKey?.type?.classId
      } else {
        requestingBinding.originClassId
      }
    if (targetClassId != null) addAll(requestSources.byOrigin[targetClassId])
    for (memberOwner in requestingBinding.memberInjectionOwnerIds) {
      addAll(requestSources.byMemberOwner[memberOwner])
    }
    if (result.isEmpty()) return listOf(requestingBinding.pointer)
    return result.values.toList()
  }

  /** Factory annotations matter even when a qualifier or explicit provider changes lookup. */
  private fun assistedFactoryFor(request: KaContextualTypeKey): KaBinding.AssistedFactory? {
    val type = request.typeKey.type
    if (lazyFactoryTypes.containsKey(type)) return lazyFactoryTypes[type]
    val factory = index.assistedFactoryForType(request.typeKey, queryContext)
    lazyFactoryTypes[type] = factory
    return factory
  }

  /** Renders the same restriction as the compiler's assisted-factory injection-site check. */
  private fun reportLazyAssistedFactory(
    factory: KaBinding.AssistedFactory,
    request: KaContextualTypeKey,
    requestingBinding: KaBinding?,
    stack: KaBindingStack,
    sourcePointer: KaSourcePointer,
  ) {
    val source = LazyFactorySite(index.pointerIdentity(sourcePointer) ?: sourcePointer, request)
    val reported =
      reportedLazyFactorySites
        ?: HashSet<LazyFactorySite>().also {
          reportedLazyFactorySites = it
        }
    if (!reported.add(source)) return

    val classId = factory.typeKey.type.classId
    val factoryName = classId?.shortClassName?.asString() ?: factory.typeKey.type.shortType
    val qualifiedFactoryName =
      classId?.relativeClassName?.asString() ?: factory.typeKey.type.shortType
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.INVALID_BINDING,
        severity = MetroSeverity.ERROR,
        title =
          textOf(
            "Metro does not support injecting Lazy<$factoryName> because " +
              "$qualifiedFactoryName is an @AssistedFactory-annotated type."
          ),
      )
    val diagnosticStack = stack.copy()
    val firstEntry = diagnosticStack.entries.firstOrNull()
    if (firstEntry == null) {
      diagnosticStack.push(KaBindingStack.Entry(request, pointer = sourcePointer))
    } else {
      diagnosticStack.pop()
      diagnosticStack.push(firstEntry.withPointer(sourcePointer))
    }
    pendingRelated = listOfNotNull(factory, requestingBinding)
    try {
      report(diagnostic, diagnosticStack)
    } finally {
      pendingRelated = emptyList()
    }
  }

  private data class LazyFactorySite(
    val source: Any,
    val request: KaContextualTypeKey,
  )

  private fun buildStackToRoot(
    key: KaTypeKey,
    diagnosticRoutes: KaDiagnosticRoutes,
    fallback: KaBindingStack,
  ): KaBindingStack {
    val route =
      diagnosticRoutes.routeToRoot(
        key,
        createDependencyEntry = { callingKey, dependencyKey ->
          val callingBinding = checkNotNull(realGraph.bindings[callingKey])
          val contextKey =
            callingBinding.dependencies.first { dependency ->
              dependency.typeKey == dependencyKey
            }
          KaBindingStack.Entry.injectedAt(
            contextKey,
            callingBinding,
            sources.location(callingBinding),
          )
        },
        ensureActive = ProgressManager::checkCanceled,
      )
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
                append(sources.location(binding) ?: "<unknown>")
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
              location = sources.location(binding),
            )
        }
      }
    }

    return MissingBindingHints(notes = notes, similarBindings = similar)
  }
}

/** A key an extension child delegates to its parent seal, anchored at the child declaration. */
internal class ReservedParentKey(
  val key: KaTypeKey,
  val pointer: SmartPsiElementPointer<out PsiElement>,
  val binding: KaBinding,
)

/** Thrown by [KaBindingGraph.reportFatal] and caught by [KaBindingGraph.seal]. */
private class SealAborted : RuntimeException() {
  // Control flow only, so skip the expensive stack trace capture
  override fun fillInStackTrace(): Throwable = this
}
