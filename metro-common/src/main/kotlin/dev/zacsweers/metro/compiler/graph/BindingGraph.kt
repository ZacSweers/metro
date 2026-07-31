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
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.diagnostics.Style
import dev.zacsweers.metro.compiler.diagnostics.TraceEntry
import dev.zacsweers.metro.compiler.diagnostics.buildText
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.getValue
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.SortedMap
import java.util.SortedSet
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
   * Creates bindings for keys not necessarily manually added to the graph (like
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
  private val missingBindingHints: (key: TypeKey) -> MissingBindingHints = {
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
) : BindingGraph<Type, TypeKey, ContextualTypeKey, Binding, BindingStackEntry, BindingStack> {
  // Populated by initial graph setup and later seal()
  override val bindings: MutableScatterMap<TypeKey, Binding> = MutableScatterMap(256)
  private val bindingIndices = MutableObjectIntMap<TypeKey>()
  private val reportedMissingKeys = mutableSetOf<TypeKey>()

  public var sealed: Boolean = false
    private set

  /**
   * Finalizes the binding graph by performing validation and cache initialization.
   *
   * This function operates in a two-step process:
   * 1. Validates the binding graph by performing a [metroSort]. Cycles that involve deferrable
   *    types, such as `Lazy` or `Provider`, are allowed and deferred for special handling at
   *    code-generation-time and store any deferred types in [GraphTopology.deferredTypes]. Any
   *    strictly invalid cycles or missing bindings result in an error being thrown.
   * 2. The returned topologically sorted list is then processed to compute [bindingIndices] and
   *    [GraphTopology.deferredTypes]. Any dependency whose index is later than the current index is
   *    presumed a valid cycle indicator and thus that type must be deferred.
   *
   * This operation runs in O(V+E). After calling this function, the binding graph becomes
   * immutable.
   *
   * Reports errors to [errorReporter] if a strict dependency cycle or missing binding is
   * encountered during validation.
   *
   * @param onPopulated a callback for when the graph is fully populated but not yet validated.
   * @param validateBindings a callback to perform optional extra validation on bindings
   *   post-adjacency build.
   * @param keep optional set of keys to keep, even if they are unused.
   */
  context(traceScope: TraceScope)
  public fun seal(
    roots: Map<ContextualTypeKey, BindingStackEntry> = emptyMap(),
    keep: Map<ContextualTypeKey, BindingStackEntry> = emptyMap(),
    shrinkUnusedBindings: Boolean = true,
    onPopulated: () -> Unit = {},
    onSortedCycle: (List<TypeKey>) -> Unit = {},
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
  ): GraphTopology<TypeKey> {
    val stack = newBindingStack()

    // Order matters, prefer roots over matching kees as they have more information in their entries
    val rootsWithKeeps = keep + roots
    val missingBindings = populateGraph(rootsWithKeeps, stack)

    onPopulated()

    sealed = true

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
          sourceToTarget = { key -> bindings.getValue(key).dependencies.map { it.typeKey } },
        ) { source, missing ->
          val binding = bindings.getValue(source)
          val contextKey = binding.dependencies.first { it.typeKey == missing }
          if (!contextKey.hasDefault) {
            val stackCopy = stack.copy()
            val stackEntry = stackCopy.newBindingStackEntry(contextKey, binding, roots)

            // If there's a root entry for the missing binding, add it into the stack too
            val matchingRootEntry =
              roots.entries.firstOrNull { it.key.typeKey == binding.typeKey }?.value
            matchingRootEntry?.let { stackCopy.push(it) }
            stackCopy.withEntry(stackEntry) { reportMissingBinding(missing, stackCopy) }
          }
        }
      }

    // Report all missing bindings _after_ building adjacency so we can backtrace where possible
    missingBindings.forEach { (key, stack) -> reportMissingBinding(key, stack) }

    val topo =
      trace("Sort and validate") {
        val allKeeps =
          if (shrinkUnusedBindings) {
            keep.keys.mapToSet { it.typeKey }
          } else {
            fullAdjacency.keys + keep.keys.mapToSet { it.typeKey }
          }
        sortAndValidate(roots, allKeeps, fullAdjacency, stack, onSortedCycle)
      }

    // Validate bindings using the reachable adjacency computed during topo sort.
    // This is more efficient as it only includes reachable bindings/edges.
    validateBindings(bindings, stack, roots, topo.adjacency)

    trace("Compute binding indices") {
      // If it depends itself or something that comes later in the topo sort, it
      // must be deferred. This is how we handle cycles that are broken by deferrable
      // types like Provider/Lazy/...
      // O(1) ("does A depend on B?")
      topo.sortedKeys.forEachIndexed { i, key -> bindingIndices.put(key, i) }
    }

    return topo
  }

  context(traceScope: TraceScope)
  private fun populateGraph(
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    stack: BindingStack,
  ): Map<TypeKey, BindingStack> {
    // Traverse all the bindings up front to
    // First ensure all the roots' bindings are present
    // Defer missing binding reporting until after we finish populating
    val missingBindings = mutableMapOf<TypeKey, BindingStack>()
    for ((contextKey, entry) in roots) {
      if (contextKey.typeKey !in bindings) {
        val bindings = computeBindings(contextKey, bindings, stack)
        if (bindings.isNotEmpty()) {
          for (binding in bindings) {
            tryPut(binding, stack, binding.typeKey)
          }
        } else if (!contextKey.hasDefault) {
          stack.withEntry(entry) { missingBindings[contextKey.typeKey] = stack.copy() }
        }
      }
    }

    // Then populate the rest of the bindings. This is important to do because some bindings
    // are computed (i.e., constructor-injected types) as they are used. We do this upfront
    // so that the graph is fully populated before we start validating it and avoid mutating
    // it while we're validating it.
    val bindingQueue = ArrayDeque<Binding>(bindings.size * 2).apply { bindings.forEachValue(::add) }

    // Tracks type keys whose dependencies have already been walked. Bindings may appear in the
    // queue more than once (when multiple paths resolve to the same class-based binding or
    // shared member-injector ancestors) and this avoids re-walking their dependency lists.
    val visited = HashSet<TypeKey>(bindings.size)

    trace("Populate bindings") {
      while (bindingQueue.isNotEmpty()) {
        val binding = bindingQueue.removeFirst()
        val typeKey = binding.typeKey
        if (typeKey !in bindings && !binding.isTransient) {
          bindings[typeKey] = binding
        }

        if (!visited.add(typeKey)) continue

        for (depKey in binding.dependencies) {
          val typeKey = depKey.typeKey
          // Fast path: if the dependency already has a binding we have nothing to do. Skip the
          // stack-entry allocation + push/pop which are only needed for missing-binding reports
          // and error paths inside computeBindings. Avoid repeat loops for valid cycles
          if (typeKey in bindings) continue
          stack.withEntry(stack.newBindingStackEntry(depKey, binding, roots)) {
            // If the binding isn't present, we'll report it later
            val newBindings =
              trace("Compute new bindings") { computeBindings(depKey, bindings, stack) }
            if (newBindings.isNotEmpty()) {
              for (newBinding in newBindings) {
                bindingQueue.addLast(newBinding)
              }
            } else if (depKey.hasDefault) {
              // Do nothing here, it has a default value and missing is ok
            } else {
              missingBindings[typeKey] = stack.copy()
            }
          }
        }
      }
    }

    return missingBindings
  }

  context(traceScope: TraceScope)
  private fun sortAndValidate(
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    keep: Set<TypeKey>,
    fullAdjacency: SortedMap<TypeKey, SortedSet<TypeKey>>,
    stack: BindingStack,
    onSortedCycle: (List<TypeKey>) -> Unit,
  ): GraphTopology<TypeKey> {
    val sortedRootKeys =
      TreeSet<TypeKey>().apply {
        roots.keys.forEach { add(it.typeKey) }
        addAll(keep)
      }

    // Run topo sort. It gives back either a valid order or calls onCycle for errors
    val result =
      trace("Topo sort") {
        metroSort(
          fullAdjacency = fullAdjacency,
          roots = sortedRootKeys,
          isDeferrable = { from, to ->
            if (bindings.getValue(to).isImplicitlyDeferrable) {
              true
            } else {
              bindings.getValue(from).dependencies.first { it.typeKey == to }.isDeferrable
            }
          },
          onSortedCycle = onSortedCycle,
          onCycle = { sccVertices ->
            val sccSet = sccVertices.toSet()
            val isHardEdge: (TypeKey, TypeKey) -> Boolean = { from, to ->
              val toBinding = bindings.getValue(to)
              if (toBinding.isImplicitlyDeferrable) false
              else bindings.getValue(from).dependencies.any { it.typeKey == to && !it.isDeferrable }
            }

            val cyclePath: List<TypeKey> =
              sccVertices.firstNotNullOfOrNull { candidate ->
                findSimpleCycle(
                  startNode = candidate,
                  sccNodes = sccSet,
                  fullAdjacency = fullAdjacency,
                  isEdgeAllowed = isHardEdge,
                )
              } ?: sccVertices

            val entriesInCycle = buildList {
              val size = cyclePath.size
              for (i in 0..size) {
                val currentDep = cyclePath[i % size]
                val prevReq = if (i == 0) cyclePath.last() else cyclePath[i - 1]
                val callingBinding = bindings.getValue(prevReq)
                val contextKey =
                  callingBinding.dependencies.firstOrNull {
                    it.typeKey == currentDep && !it.isDeferrable
                  }
                    ?: reportCompilerBug(
                      "Found a hard cycle, but no scalar dependency exists from " +
                        "${prevReq.render(short = true)} to ${currentDep.render(short = true)}."
                    )
                add(stack.newBindingStackEntry(contextKey, callingBinding, roots))
              }
            }

            val suspendCycleKey = findSuspendCycleKey(cyclePath, bindings)
            reportCycle(entriesInCycle, stack, suspendCycleKey)
          },
          isImplicitlyDeferrable = { key -> bindings.getValue(key).isImplicitlyDeferrable },
        )
      }

    return result
  }

  private fun <V : Comparable<V>> findSimpleCycle(
    startNode: V,
    sccNodes: Set<V>,
    fullAdjacency: Map<V, Set<V>>,
    isEdgeAllowed: (from: V, to: V) -> Boolean,
  ): List<V>? {
    val parents = mutableMapOf<V, V>()
    val queue = ArrayDeque<V>().apply { add(startNode) }
    val visited = mutableSetOf<V>()

    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      val neighbors = fullAdjacency[current].orEmpty()
      for (neighbor in neighbors) {
        if (neighbor !in sccNodes || !isEdgeAllowed(current, neighbor)) continue
        if (neighbor == startNode) {
          val cycle = mutableListOf<V>()
          var curr: V? = current
          while (curr != null) {
            cycle.add(curr)
            curr = parents[curr]
          }
          return cycle.reversed()
        }
        if (neighbor !in visited) {
          visited.add(neighbor)
          parents[neighbor] = current
          queue.addLast(neighbor)
        }
      }
    }
    return null
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
                      "you can break the cycle by injecting a deferred type at one edge, e.g. "
                    )
                    appendCode("() -> $deferredExample")
                    append(" or ")
                    appendCode("Lazy<$deferredExample>")
                  }
                  append(". Only do this if you know what you're doing though!")
                }
              )
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
    val locations = bindings.map { it.renderLocationDiagnostic(short = true) }
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
              )
            )
            bindingStack.toTraceSection()?.let(::add)
          },
        notes =
          extraNotes +
            Note.help(
              "remove or disambiguate the duplicate bindings (e.g. with distinct qualifiers), " +
                "or use @IntoSet/@IntoMap if you intended a multibinding"
            ),
      )
    errorReporter.report(diagnostic, bindingStack)
  }

  override operator fun get(key: TypeKey): Binding? = bindings[key]

  override operator fun contains(key: TypeKey): Boolean = bindings.containsKey(key)

  // O(1) after seal()
  override fun TypeKey.dependsOn(other: TypeKey): Boolean {
    return bindingIndices[this] >= bindingIndices[other]
  }

  public fun reportMissingBinding(typeKey: TypeKey, bindingStack: BindingStack) {
    if (reportedMissingKeys.add(typeKey)) {
      val hints = missingBindingHints(typeKey)
      // Don't have access to an IrPluginContext here to check it's an anyType
      val isAnyType = typeKey.render(short = false) == "kotlin.Any"
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
                    hints.similarBindings.sortedBy { it.key.toString() }
                  )
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
                    " has an @Inject constructor or is provided by an @Provides or @Binds declaration visible to "
                  )
                  append(graphName ?: "this graph", Style.EMPHASIS)
                }
              ),
        )
      errorReporter.report(diagnostic, bindingStack)
    }
  }
}
