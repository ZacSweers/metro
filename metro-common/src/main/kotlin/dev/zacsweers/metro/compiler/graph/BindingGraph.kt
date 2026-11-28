// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.MutableObjectIntMap
import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.allElementsAreEqual
import dev.zacsweers.metro.compiler.diagnostics.CycleNode
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticHeadlines
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSection
import dev.zacsweers.metro.compiler.diagnostics.LocatedItem
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.diagnostics.Style
import dev.zacsweers.metro.compiler.diagnostics.TraceEntry
import dev.zacsweers.metro.compiler.diagnostics.buildText
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.getValue
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.Arrays
import java.util.BitSet
import java.util.TreeSet
import org.jetbrains.kotlin.name.FqName

/**
 * The generic binding graph contract shared by the compiler and IDE plugin.
 *
 * This API uses androidx.collection types, and metro-common declares that dependency as
 * compileOnly. Consumers must provide androidx.collection (and androidx.tracing for the tracing
 * package) on their own classpath.
 */
public interface BindingGraph<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, TypeKey>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, ContextualTypeKey>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
  BindingStackEntry : BaseBindingStack.BaseEntry<Type, TypeKey, ContextualTypeKey>,
  BindingStack : BaseBindingStack<*, Type, TypeKey, BindingStackEntry, BindingStack>,
> {
  public val bindings: ScatterMap<TypeKey, Binding>

  public operator fun get(key: TypeKey): Binding?

  public operator fun contains(key: TypeKey): Boolean

  public fun TypeKey.dependsOn(other: TypeKey): Boolean
}

// TODO instead of implementing BindingGraph, maybe just make this a builder and have build()
//  produce one?
public open class MutableBindingGraph<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, TypeKey>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, ContextualTypeKey>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
  BindingStackEntry : BaseBindingStack.BaseEntry<Type, TypeKey, ContextualTypeKey>,
  BindingStack : BaseBindingStack<*, Type, TypeKey, BindingStackEntry, BindingStack>,
>(
  private val newBindingStack: () -> BindingStack,
  private val newBindingStackEntry:
    BindingStack.(
      contextKey: ContextualTypeKey,
      callingBinding: Binding?,
      roots: Map<ContextualTypeKey, BindingStackEntry>,
    ) -> BindingStackEntry,
  /**
   * Creates bindings for keys not necessarily manually added to the graph (e.g.,
   * constructor-injected types). Note one key may incur the creation of multiple bindings, so this
   * returns a set.
   */
  private val computeBindings:
    (
      contextKey: ContextualTypeKey,
      currentBindings: ScatterMap<TypeKey, Binding>,
      stack: BindingStack,
    ) -> Set<Binding> =
    { _, _, _ ->
      emptySet()
    },
  private val errorReporter: ErrorReporter<BindingStack> = ErrorReporter.throwing(),
  private val missingBindingDiagnosticDetails: (key: TypeKey) -> MissingBindingHints = {
    MissingBindingHints()
  },
  /**
   * Returns a key in a hard cycle that requires suspend initialization, or null when the cycle is
   * synchronous. Called only after a hard cycle has been found.
   */
  private val findSuspendCycleKey:
    (cycleKeys: List<TypeKey>, currentBindings: ScatterMap<TypeKey, Binding>) -> TypeKey? =
    { _, _ ->
      null
    },
  /** Renders duplicate-binding locations from the caller's captured source information. */
  private val bindingLocationDiagnostic: (Binding) -> LocationDiagnostic = {
    it.renderLocationDiagnostic(short = true)
  },
  /**
   * Observes requests satisfied by bindings already in the graph. Dependency entries are created
   * only when this callback is present; root requests reuse their supplied entries.
   */
  private val onExistingBinding: ((ContextualTypeKey, Binding, BindingStackEntry) -> Unit)? = null,
  /**
   * Observes each request while the graph is populated, with the binding that satisfies it. The
   * calling binding is null for root requests. This runs once per dependency edge.
   */
  private val onDependencyResolved:
    ((callingBinding: Binding?, contextKey: ContextualTypeKey, binding: Binding) -> Unit)? =
    null,
  /**
   * Observes each reachable key in sorted order after sealing. A key's dependencies come before it,
   * unless a deferred dependency breaks a cycle.
   */
  private val onSortedKey: ((TypeKey) -> Unit)? = null,
  /**
   * Returns true when the caller reports a missing key with its own diagnostic. The graph then
   * skips its missing-binding report for that key.
   */
  private val reportsMissingKeyElsewhere: (TypeKey) -> Boolean = { false },
) : BindingGraph<Type, TypeKey, ContextualTypeKey, Binding, BindingStackEntry, BindingStack> {
  // Populated by initial graph setup and prepareSeal().
  override val bindings: MutableScatterMap<TypeKey, Binding> = MutableScatterMap(256)
  private val bindingIndices = MutableObjectIntMap<TypeKey>()
  private val reportedMissingKeys = mutableSetOf<TypeKey>()

  public var sealed: Boolean = false
    private set

  /**
   * Resolves bindings and captures the graph data needed by worker analysis.
   *
   * This phase reports missing bindings and seals the graph against further additions. Binding
   * getters and population callbacks run here on the calling thread. It also assigns vertex ids and
   * captures [PreparedGraphSeal.analysisInput], so analysis never reads a key.
   *
   * Analysis runs [analyzePreparedGraph] to find a valid order and cycles that require deferred
   * initialization. Types such as `Lazy` and `Provider` can break cycles through deferred
   * construction. The result records those types in [GraphTopology.deferredTypes] for code
   * generation.
   *
   * [PreparedGraphSeal.finish] reports strict cycles to [errorReporter] and validates the reachable
   * bindings. It also computes [bindingIndices] from the sorted order. A dependency whose index is
   * later than its consumer requires deferred initialization.
   *
   * @param onPopulated a callback for when the graph is fully populated but not yet validated.
   * @param validateBindings a callback to perform optional extra validation during finalization.
   * @param keep optional set of keys to keep, even if they are unused.
   * @param ensureActive cancellation callback invoked throughout graph validation.
   */
  context(traceScope: TraceScope)
  public fun prepareSeal(
    roots: Map<ContextualTypeKey, BindingStackEntry> = emptyMap(),
    keep: Map<ContextualTypeKey, BindingStackEntry> = emptyMap(),
    shrinkUnusedBindings: Boolean = true,
    onPopulated: () -> Unit = {},
    onSortedCycle: (List<TypeKey>) -> Unit = {},
    ensureActive: () -> Unit = {},
    validateBindings:
      (
        bindings: ScatterMap<TypeKey, Binding>,
        stack: BindingStack,
        roots: Map<ContextualTypeKey, BindingStackEntry>,
        adjacency: GraphAdjacency<TypeKey>,
      ) -> Unit =
      { _, _, _, _ ->
        /* noop */
      },
  ): PreparedGraphSeal<TypeKey> {
    val stack = newBindingStack()

    // Roots take precedence over matching keeps because their entries have more information.
    val rootsWithKeeps = keep + roots
    val missingBindings = populateGraph(rootsWithKeeps, stack, ensureActive)

    onPopulated()

    sealed = true

    val deferrableDependencies = HashMap<TypeKey, Set<TypeKey>>()
    val implicitlyDeferrableKeys = HashSet<TypeKey>()
    val missingEdges = mutableMapOf<TypeKey, MutableMap<TypeKey, ContextualTypeKey>>()

    /**
     * Build the full adjacency mapping of keys to all their dependencies.
     *
     * Note that `onMissing` will gracefully allow missing targets that have default values (i.e.,
     * optional bindings).
     */
    val fullAdjacency =
      trace("Build adjacency list") {
        buildFullAdjacency(
          map = bindings,
          sourceToTarget = { key ->
            val binding = bindings.getValue(key)
            if (binding.isImplicitlyDeferrable) {
              implicitlyDeferrableKeys += key
            }
            val dependencies = binding.dependencies
            // The snapshot is complete when adjacency exhausts each source.
            sequence {
              var deferrableTargets: MutableSet<TypeKey>? = null
              for (dependency in dependencies) {
                ensureActive()
                val targetKey = dependency.typeKey
                if (dependency.isDeferrable) {
                  if (deferrableTargets == null) {
                    deferrableTargets = HashSet()
                  }
                  deferrableTargets.add(targetKey)
                }
                yield(targetKey)
              }

              val targets = deferrableTargets
              if (targets != null) {
                // Any eager parallel request takes precedence regardless of request order.
                for (dependency in dependencies) {
                  ensureActive()
                  if (!dependency.isDeferrable) {
                    targets.remove(dependency.typeKey)
                  }
                }
                if (targets.isNotEmpty()) {
                  deferrableDependencies[key] = targets
                }
              }
            }
              .asIterable()
          },
          ensureActive = ensureActive,
        ) { source, missing ->
          val binding = bindings.getValue(source)
          // Adjacency merges requests for the same type. Any required request must be reported.
          val contextKey =
            binding.dependencies.firstOrNull {
              ensureActive()
              it.typeKey == missing && !it.hasDefault
            }
          if (contextKey != null) {
            missingEdges.getOrPut(source) { mutableMapOf() }[missing] = contextKey
            missingBindings.getOrPut(missing) {
              stack.newBindingStackEntry(contextKey, binding, roots)
            }
          }
        }
      }

    if (missingBindings.isNotEmpty()) {
      reportMissingBindings(roots, fullAdjacency, missingEdges, missingBindings, ensureActive)
    }

    val sortedRootKeys = TreeSet<TypeKey>()
    for (key in roots.keys) {
      ensureActive()
      sortedRootKeys += key.typeKey
    }
    if (!shrinkUnusedBindings) {
      for (key in fullAdjacency.keys) {
        ensureActive()
        sortedRootKeys += key
      }
    }
    for (key in keep.keys) {
      ensureActive()
      sortedRootKeys += key.typeKey
    }

    // Ids and edge kinds are captured here so analysis never touches a key.
    val indexed = trace("Assign vertex ids") { fullAdjacency.indexed(ensureActive) }
    val implicitIds = idsOf(indexed, implicitlyDeferrableKeys, ensureActive)
    val input =
      AnalysisInput(
        vertexCount = indexed.vertexCount,
        rowStarts = indexed.rowStarts,
        targets = indexed.targets,
        deferredEdges = deferredEdgesOf(indexed, deferrableDependencies, implicitIds, ensureActive),
        implicitlyDeferrable = implicitIds,
        roots = indexed.rootIds(sortedRootKeys, ensureActive),
      )
    val keysOf: (IntArray) -> List<TypeKey> = indexed::keysOf
    val topologyOf: (AnalysisOutput) -> GraphTopology<TypeKey> = { output ->
      indexed.topologyOf(output, ensureActive)
    }

    return object : PreparedGraphSeal<TypeKey>() {
      override val analysisInput: AnalysisInput = input

      context(traceScope: TraceScope)
      override fun analyze(): GraphAnalysis<TypeKey> =
        GraphAnalysis(analyzePreparedGraph(input, ensureActive), keysOf, topologyOf)

      context(traceScope: TraceScope)
      override fun finish(output: AnalysisOutput): GraphTopology<TypeKey> =
        finish(GraphAnalysis(output, keysOf, topologyOf))

      context(traceScope: TraceScope)
      override fun finish(analysis: GraphAnalysis<TypeKey>): GraphTopology<TypeKey> {
        for (cycle in analysis.sortedCycles) {
          ensureActive()
          onSortedCycle(cycle)
        }
        val hardCycle = analysis.hardCycle
        if (hardCycle != null) {
          reportHardCycle(hardCycle, stack, roots, ensureActive)
        }
        val topo = checkNotNull(analysis.topology)

        // Validate bindings using the reachable adjacency from analysis.
        // This is more efficient as it only includes reachable bindings/edges.
        validateBindings(bindings, stack, roots, topo.adjacency)

        trace("Compute binding indices") {
          // If it depends itself or something that comes later in the topo sort, it
          // must be deferred. This is how we handle cycles that are broken by deferrable
          // types like Provider/Lazy/...
          // O(1) ("does A depend on B?")
          topo.sortedKeys.forEachIndexed { i, key ->
            ensureActive()
            bindingIndices.put(key, i)
            onSortedKey?.invoke(key)
          }
        }

        return topo
      }
    }
  }

  /** Reports the shortest route to each missing key, or its immediate request if unreachable. */
  private fun reportMissingBindings(
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    adjacency: Map<TypeKey, Set<TypeKey>>,
    missingEdges: Map<TypeKey, Map<TypeKey, ContextualTypeKey>>,
    missingBindings: Map<TypeKey, BindingStackEntry>,
    ensureActive: () -> Unit,
  ) {
    // Include missing required dependencies only in the diagnostic index.
    val diagnosticAdjacency =
      adjacency +
        missingEdges.mapValues { (source, targets) ->
          ensureActive()
          adjacency.getValue(source) + targets.keys
        }

    // An optional root with a fallback can't explain why a dependency is required.
    val diagnosticRoots = roots.filterKeys { !it.hasDefault || it.typeKey in bindings }
    val routes = DiagnosticRoutes(diagnosticRoots, diagnosticAdjacency)

    for ((missing, fallback) in missingBindings) {
      ensureActive()
      val stack = newBindingStack()
      val route =
        routes.routeToRoot(missing, ensureActive) { source, target ->
          val binding = bindings.getValue(source)
          val request =
            missingEdges[source]?.get(target)
              ?: binding.dependencies.first {
                ensureActive()
                it.typeKey == target
              }
          stack.newBindingStackEntry(request, binding, roots)
        }

      if (route.isEmpty()) {
        stack.push(fallback)
      } else {
        route.forEach(stack::push)
      }

      reportMissingBinding(missing, stack)
    }
  }

  /** Resolves graph dependencies and saves a request site for each unresolved required key. */
  context(traceScope: TraceScope)
  private fun populateGraph(
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    stack: BindingStack,
    ensureActive: () -> Unit,
  ): MutableMap<TypeKey, BindingStackEntry> {
    // Traverse all the bindings up front to
    // First ensure all the roots' bindings are present
    // Defer missing binding reporting until after we finish populating
    val missingBindings = mutableMapOf<TypeKey, BindingStackEntry>()
    for ((contextKey, entry) in roots) {
      ensureActive()
      if (contextKey.typeKey !in bindings) {
        val bindings = computeBindings(contextKey, bindings, stack)
        if (bindings.isNotEmpty()) {
          for (binding in bindings) {
            ensureActive()
            tryPut(binding, stack, binding.typeKey)
          }
          onDependencyResolved?.let { onResolved ->
            bindings
              .firstOrNull { it.typeKey == contextKey.typeKey }
              ?.let { binding ->
                onResolved(null, contextKey, binding)
              }
          }
        } else if (!contextKey.hasDefault) {
          missingBindings[contextKey.typeKey] = entry
        }
      } else {
        val binding = bindings.getValue(contextKey.typeKey)
        onExistingBinding?.invoke(contextKey, binding, entry)
        onDependencyResolved?.invoke(null, contextKey, binding)
      }
    }

    // Then populate the rest of the bindings. This is important to do because some bindings
    // are computed (i.e., constructor-injected types) as they are used. We do this upfront
    // so that the graph is fully populated before we start validating it and avoid mutating
    // it while we're validating it.
    val bindingQueue = ArrayDeque<Binding>(bindings.size * 2)
    bindings.forEachValue { binding ->
      ensureActive()
      bindingQueue.add(binding)
    }

    // Tracks type keys whose dependencies have already been walked. Bindings may appear in the
    // queue more than once (e.g. when multiple paths resolve to the same class-based binding or
    // shared member-injector ancestors) and this avoids re-walking their dependency lists.
    val visited = HashSet<TypeKey>(bindings.size)

    trace("Populate bindings") {
      while (bindingQueue.isNotEmpty()) {
        ensureActive()
        val binding = bindingQueue.removeFirst()
        val typeKey = binding.typeKey
        if (typeKey !in bindings && !binding.isTransient) {
          bindings[typeKey] = binding
        }

        if (!visited.add(typeKey)) continue

        for (depKey in binding.dependencies) {
          ensureActive()
          val typeKey = depKey.typeKey
          // Fast path: existing dependencies need no lookup. Stack entries support reporting,
          // and are allocated here only for an observer. Avoid repeat loops for valid cycles.
          if (typeKey in bindings) {
            val existing = bindings.getValue(typeKey)
            onExistingBinding?.invoke(
              depKey,
              existing,
              stack.newBindingStackEntry(depKey, binding, roots),
            )
            onDependencyResolved?.invoke(binding, depKey, existing)
            continue
          }
          val entry = stack.newBindingStackEntry(depKey, binding, roots)
          stack.withEntry(entry) {
            // If the binding isn't present, we'll report it later
            val newBindings =
              trace("Compute new bindings") { computeBindings(depKey, bindings, stack) }
            if (newBindings.isNotEmpty()) {
              for (newBinding in newBindings) {
                ensureActive()
                bindingQueue.addLast(newBinding)
              }
              onDependencyResolved?.let { onResolved ->
                newBindings
                  .firstOrNull { it.typeKey == typeKey }
                  ?.let { resolved ->
                    onResolved(binding, depKey, resolved)
                  }
              }
            } else if (depKey.hasDefault) {
              // Do nothing here, it has a default value and missing is ok
            } else {
              missingBindings[typeKey] = entry
            }
          }
        }
      }
    }

    return missingBindings
  }

  /**
   * Marks the edges that may break a cycle. An edge qualifies when its target is implicitly
   * deferrable or every request for it from its source is deferrable.
   */
  private fun deferredEdgesOf(
    indexed: IndexedAdjacency<TypeKey>,
    deferrableDependencies: Map<TypeKey, Set<TypeKey>>,
    implicitIds: BitSet,
    ensureActive: () -> Unit,
  ): BitSet {
    val targets = indexed.targets
    val deferredEdges = BitSet(targets.size)

    if (!implicitIds.isEmpty) {
      for (edge in targets.indices) {
        ensureActive()
        if (implicitIds.get(targets[edge])) {
          deferredEdges.set(edge)
        }
      }
    }

    for ((source, deferrableTargets) in deferrableDependencies) {
      ensureActive()
      // A source without a vertex has no edges to mark.
      val sourceId = indexed.idOf.getOrDefault(source, -1)
      if (sourceId == -1) {
        continue
      }

      val rowStart = indexed.rowStarts[sourceId]
      val rowEnd = indexed.rowStarts[sourceId + 1]
      for (target in deferrableTargets) {
        ensureActive()
        // Optional targets without a binding have no edge.
        val targetId = indexed.idOf.getOrDefault(target, -1)
        if (targetId == -1) {
          continue
        }

        val edge = Arrays.binarySearch(targets, rowStart, rowEnd, targetId)
        if (edge >= 0) {
          deferredEdges.set(edge)
        }
      }
    }

    return deferredEdges
  }

  /** Marks the ids of [keys] that are in the graph. */
  private fun idsOf(
    indexed: IndexedAdjacency<TypeKey>,
    keys: Set<TypeKey>,
    ensureActive: () -> Unit,
  ): BitSet {
    val ids = BitSet(indexed.vertexCount)
    for (key in keys) {
      ensureActive()
      val id = indexed.idOf.getOrDefault(key, -1)
      if (id != -1) {
        ids.set(id)
      }
    }
    return ids
  }

  /** Rebuilds diagnostic entries after worker analysis has found a strict cycle. */
  private fun reportHardCycle(
    cyclePath: List<TypeKey>,
    stack: BindingStack,
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    ensureActive: () -> Unit,
  ): Nothing {
    val entriesInCycle = buildList {
      val size = cyclePath.size
      for (i in 0..size) {
        ensureActive()
        val currentDep = cyclePath[i % size]
        val prevReq =
          if (i == 0) {
            cyclePath.last()
          } else {
            cyclePath[i - 1]
          }
        val callingBinding = bindings.getValue(prevReq)
        val contextKey =
          callingBinding.dependencies.firstOrNull {
            it.typeKey == currentDep && !it.isDeferrable
          }
            ?: reportCompilerBug(
              "Found a hard cycle, but no scalar dependency exists from " +
                "${prevReq.render(short = true)} to ${currentDep.render(short = true)}.",
            )
        add(stack.newBindingStackEntry(contextKey, callingBinding, roots))
      }
    }

    val suspendCycleKey = findSuspendCycleKey(cyclePath, bindings)
    reportCycle(entriesInCycle, stack, suspendCycleKey)
  }

  private fun reportCycle(
    fullCycle: List<BindingStackEntry>,
    stack: BindingStack,
    suspendCycleKey: TypeKey?,
  ): Nothing {
    // The cycle list closes back on its first key; the loop drawing makes that explicit, so drop
    // the repeated final node.
    val isSelfCycle = fullCycle.size == 2
    val nodeEntries =
      when {
        isSelfCycle -> fullCycle.take(1)
        fullCycle.size > 1 && fullCycle.last().typeKey == fullCycle.first().typeKey ->
          fullCycle.dropLast(1)
        else -> fullCycle
      }

    val nodes = nodeEntries.map { entry ->
      CycleNode(
        name = entry.contextKey.toText(),
        aliasEdgeToNext = bindings.getValue(entry.typeKey).isAlias,
      )
    }

    val entriesToReport = if (isSelfCycle) fullCycle.take(1) else fullCycle
    val trace =
      DiagnosticSection.BindingTrace(
        graphName = stack.graphFqName.asString(),
        entries =
          buildList {
            entriesToReport.mapTo(this) { it.toTraceEntry() }
            if (entriesToReport.size > 1) {
              // The trace loops; make that visible like the cycle drawing above.
              add(TraceEntry(key = textOf("..."), usage = null, context = null))
            }
          },
      )

    val suspendDeferredExample = suspendCycleKey?.let { key ->
      nodeEntries.first { it.typeKey == key }.contextKey.toText()
    }
    val deferredExample = suspendDeferredExample ?: nodes.first().name
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.DEPENDENCY_CYCLE,
        severity = MetroSeverity.ERROR,
        title =
          buildText {
            append(DiagnosticHeadlines.DEPENDENCY_CYCLE_PREFIX)
            append(stack.graphFqName.asString(), Style.EMPHASIS)
          },
        sections = listOf(DiagnosticSection.Cycle(nodes), trace),
        notes =
          buildList {
            addAll(entriesToReport.flatMap { it.diagnosticNotes }.distinct())
            add(
              Note.help(
                buildText {
                  if (suspendDeferredExample != null) {
                    append("this cycle requires suspend initialization. Defer one edge with ")
                    appendCode("suspend () -> $deferredExample")
                    append(" or ")
                    appendCode("SuspendLazy<$deferredExample>")
                  } else {
                    append(
                      "you can break the cycle by injecting a deferred type at one edge, e.g. ",
                    )
                    appendCode("() -> $deferredExample")
                    append(" or ")
                    appendCode("Lazy<$deferredExample>")
                  }
                  append(". Only do this if you know what you're doing though!")
                },
              ),
            )
          },
      )
    errorReporter.reportFatal(diagnostic, stack)
  }

  public fun replace(binding: Binding) {
    bindings[binding.typeKey] = binding
  }

  /**
   * @param key The key to put the binding under. Can be customized to link/alias a key to another
   *   binding
   */
  public fun tryPut(binding: Binding, bindingStack: BindingStack, key: TypeKey = binding.typeKey) {
    check(!sealed) { "Graph already sealed" }
    if (binding.isTransient) {
      // Absent binding or otherwise not something we store
      return
    }
    if (key in bindings) {
      val existing = bindings.getValue(key)
      reportDuplicateBindings(key, listOf(existing, binding), bindingStack)
    } else {
      bindings[binding.typeKey] = binding
    }
  }

  public fun reportDuplicateBindings(
    key: TypeKey,
    bindings: List<Binding>,
    bindingStack: BindingStack,
  ) {
    val locations = bindings.map(bindingLocationDiagnostic)
    val notes = buildList {
      addAll(bindings.flatMap { it.diagnosticNotes }.distinct())
      addAll(locations.flatMap { it.notes }.distinct())
      if (bindings.distinctBy { System.identityHashCode(it) }.size == 1) {
        add(Note.note("the duplicate bindings are all the same instance"))
      } else if (bindings.allElementsAreEqual()) {
        add(Note.note("the duplicate bindings are all equal"))
      }
    }
    reportDuplicateBindings(
      key,
      locations,
      bindingStack,
      notes,
    )
  }

  public fun reportDuplicateBindings(
    key: TypeKey,
    locations: List<LocationDiagnostic>,
    bindingStack: BindingStack,
    extraNotes: List<Note> = emptyList(),
  ) {
    if (locations.size < 2) {
      reportCompilerBug("Must have at least two locations to report duplicate bindings")
    }
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.DUPLICATE_BINDING,
        severity = MetroSeverity.ERROR,
        title =
          buildText {
            append(DiagnosticHeadlines.DUPLICATE_BINDING_PREFIX)
            append(key.toText())
          },
        sections =
          buildList {
            add(
              DiagnosticSection.Locations(
                header = null,
                items = locations.map { it.toLocatedItem() },
              ),
            )
            bindingStack.toTraceSection()?.let(::add)
          },
        notes =
          extraNotes +
            Note.help(
              "remove or disambiguate the duplicate bindings (e.g. with distinct qualifiers), " +
                "or use @IntoSet/@IntoMap if you intended a multibinding",
            ),
      )
    errorReporter.report(diagnostic, bindingStack)
  }

  /**
   * Reports a `Set` or `Map` [key] that has both [directBindings] for the whole collection and
   * multibinding [contributions]. Lookup would use a direct binding and drop every contribution.
   *
   * @param contributions the contributions and `@Multibinds` declarations that have a location.
   * @param unlocatedContributionCount contributions that can't be located from this graph, such as
   *   scoped contributions owned by a parent graph.
   * @param isMap true for a `Map` key, false for a `Set` key.
   */
  public fun reportMultibindingConflict(
    key: TypeKey,
    directBindings: List<Binding>,
    contributions: List<Binding>,
    unlocatedContributionCount: Int,
    isMap: Boolean,
    bindingStack: BindingStack,
  ) {
    val directLocations = directBindings.map(bindingLocationDiagnostic)
    val contributionLocations = contributions.map(bindingLocationDiagnostic)
    val collectionName = if (isMap) "Map" else "Set"
    val contributionItems = buildList {
      addAll(contributionLocations.map { it.toLocatedItem() })
      if (unlocatedContributionCount > 0) {
        val description =
          if (unlocatedContributionCount == 1) {
            "1 scoped contribution from a parent graph"
          } else {
            "$unlocatedContributionCount scoped contributions from parent graphs"
          }
        add(LocatedItem(location = null, code = null, description = textOf(description)))
      }
    }
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.MULTIBINDING_CONFLICT,
        severity = MetroSeverity.ERROR,
        title =
          buildText {
            append(key.toText())
            append(" has both a direct binding and multibinding contributions")
          },
        sections =
          buildList {
            add(
              DiagnosticSection.Locations(
                header = textOf("Direct bindings:"),
                items = directLocations.map { it.toLocatedItem() },
              ),
            )
            add(
              DiagnosticSection.Locations(
                header = textOf("$collectionName contributions and declarations:"),
                items = contributionItems,
              ),
            )
            bindingStack.toTraceSection()?.let(::add)
          },
        notes =
          (directLocations + contributionLocations).flatMap { it.notes }.distinct() +
            Note.help(
              buildText {
                append("Metro would use the direct binding and drop every contribution. ")
                append("Remove one of them, give one a qualifier, or ")
                if (isMap) {
                  append("contribute its entries with ")
                  appendCode("@IntoMap")
                } else {
                  append("contribute the whole set with ")
                  appendCode("@ElementsIntoSet")
                }
              },
            ),
      )
    errorReporter.report(diagnostic, bindingStack)
  }

  override operator fun get(key: TypeKey): Binding? = bindings[key]

  override operator fun contains(key: TypeKey): Boolean = bindings.containsKey(key)

  // Binding indices support constant-time checks after finalization.
  override fun TypeKey.dependsOn(other: TypeKey): Boolean {
    return bindingIndices[this] >= bindingIndices[other]
  }

  public fun reportMissingBinding(typeKey: TypeKey, bindingStack: BindingStack) {
    if (reportsMissingKeyElsewhere(typeKey)) {
      return
    }

    if (reportedMissingKeys.add(typeKey)) {
      val hints = missingBindingDiagnosticDetails(typeKey)
      val isAnyType = typeKey.isUnqualifiedAny
      val graphName = bindingStack.graphFqName.takeIf { it != FqName.ROOT }?.shortName()?.asString()

      val diagnostic =
        MetroDiagnostic(
          id = MetroDiagnosticId.MISSING_BINDING,
          severity = MetroSeverity.ERROR,
          title =
            buildText {
              append("No binding found for ")
              append(typeKey.toText())
            },
          sections =
            buildList {
              bindingStack.toChainSection()?.let(::add)
              bindingStack.toTraceSection()?.let(::add)
              addAll(hints.sections)
              if (hints.similarBindings.isNotEmpty() && !isAnyType) {
                add(
                  DiagnosticSection.SimilarBindings(
                    hints.similarBindings.sortedBy { it.key.toString() },
                  ),
                )
              }
            },
          notes =
            hints.notes +
              bindingStack.entries.flatMap { it.diagnosticNotes }.distinct() +
              Note.help(
                buildText {
                  append("ensure ")
                  append(typeKey.toText())
                  append(
                    " has an @Inject constructor or is provided by an @Provides or @Binds declaration visible to ",
                  )
                  append(graphName ?: "this graph", Style.EMPHASIS)
                },
              ),
        )
      errorReporter.report(diagnostic, bindingStack)
    }
  }
}
