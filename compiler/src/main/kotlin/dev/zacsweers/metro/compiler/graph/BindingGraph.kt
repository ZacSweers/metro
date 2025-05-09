// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.ir.appendBindingStack
import dev.zacsweers.metro.compiler.ir.appendBindingStackEntries
import dev.zacsweers.metro.compiler.ir.withEntry
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested

internal interface BindingGraph<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, *>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
  BindingStackEntry : BaseBindingStack.BaseEntry<Type, TypeKey, ContextualTypeKey>,
  BindingStack : BaseBindingStack<*, Type, TypeKey, BindingStackEntry>,
> {
  val snapshot: Map<TypeKey, Binding>
  val deferredTypes: Set<TypeKey>

  operator fun get(key: TypeKey): Binding?

  operator fun contains(key: TypeKey): Boolean

  fun TypeKey.dependsOn(other: TypeKey): Boolean
}

internal open class MutableBindingGraph<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, *>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
  BindingStackEntry : BaseBindingStack.BaseEntry<Type, TypeKey, ContextualTypeKey>,
  BindingStack : BaseBindingStack<*, Type, TypeKey, BindingStackEntry>,
>(
  private val newBindingStack: () -> BindingStack,
  private val newBindingStackEntry:
    BindingStack.(
      contextKey: ContextualTypeKey,
      callingBinding: Binding?,
      roots: Map<ContextualTypeKey, BindingStackEntry>,
    ) -> BindingStackEntry,
  private val absentBinding: (typeKey: TypeKey) -> Binding,
  /**
   * Creates a binding for keys not necessarily manually added to the graph (e.g.,
   * constructor-injected types).
   */
  private val computeBinding: (contextKey: ContextualTypeKey) -> Binding? =
    { _ ->
      null
    },
  private val onError: (String, BindingStack) -> Nothing = { message, stack -> error(message) },
  private val findSimilarBindings: (key: TypeKey) -> Map<TypeKey, String> = { emptyMap() },
  private val stackLogger: MetroLogger = MetroLogger.NONE,
) : BindingGraph<Type, TypeKey, ContextualTypeKey, Binding, BindingStackEntry, BindingStack> {
  // Populated by initial graph setup and later seal()
  private val bindings = mutableMapOf<TypeKey, Binding>()
  private val bindingIndices = mutableMapOf<TypeKey, Int>()

  override val deferredTypes: MutableSet<TypeKey> = mutableSetOf()

  var sealed = false
    private set

  /**
   * Finalizes the binding graph by performing validation and cache initialization.
   *
   * This function operates in a two-step process:
   * 1. Validates the binding graph to detect strict dependency cycles and ensures all required
   *    bindings are present. Cycles that involve deferrable types, such as `Lazy` or `Provider`,
   *    are allowed and deferred for special handling at code-generation-time and store any deferred
   *    types in [deferredTypes]. Any strictly invalid cycles or missing bindings result in an error
   *    being thrown.
   * 2. Calculates the transitive closure of the dependencies for each type. The transitive closure
   *    is cached for efficient lookup of indirect dependencies during graph ops after sealing.
   *
   * This operation runs in O(V+E). After calling this function, the binding graph becomes
   * immutable.
   *
   * Note: The graph traversal employs depth-first search (DFS) for dependency validation and
   * transitive closure computation.
   *
   * Calls [onError] if a strict dependency cycle or missing binding is encountered during
   * validation.
   */
  fun seal(
    roots: Map<ContextualTypeKey, BindingStackEntry> = emptyMap(),
    tracer: Tracer = Tracer.NONE,
  ): List<TypeKey> {
    val stack = newBindingStack()

    populateGraph(roots, stack, tracer)

    val topo = tracer.traceNested("Topological sort") {
      checkForCyclesAndSort(roots, stack)
    }

    tracer.traceNested("Compute deferred types") {
      // If it depends itself or something that comes later in the topo sort, it
      // must be deferred. This is how we handle cycles that are broken by deferrable
      // types like Provider/Lazy/...
      // O(1) “does A depend on B?”
      bindingIndices.putAll(topo.withIndex().associate { it.value to it.index })
      topo.forEachIndexed { currentIndex, key ->
        bindings.getValue(key).dependencies.forEach { dep ->
          // May be null if dep has a default value
          bindingIndices[dep.typeKey]?.let { depIndex ->
            if (depIndex >= currentIndex) {
              deferredTypes += key
            }
          }
        }
      }
    }

    sealed = true
    return topo
  }

  private fun populateGraph(
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    stack: BindingStack,
    tracer: Tracer,
  ) {
    // Traverse all the bindings up front to
    // First ensure all the roots' bindings are present
    for (contextKey in roots.keys) {
      computeBinding(contextKey)?.let { tryPut(it, stack, contextKey.typeKey) }
    }

    // Then populate the rest of the bindings. This is important to do because some bindings
    // are computed (i.e., constructor-injected types) as they are used. We do this upfront
    // so that the graph is fully populated before we start validating it and avoid mutating
    // it while we're validating it.
    val bindingQueue = ArrayDeque<Binding>().also { it.addAll(bindings.values) }

    tracer.traceNested("Populate bindings") {
      while (bindingQueue.isNotEmpty()) {
        val binding = bindingQueue.removeFirst()
        if (binding.typeKey !in bindings && !binding.isTransient) {
          bindings[binding.typeKey] = binding
        }

        fun Binding.visitDependencies() {
          for (depKey in dependencies) {
            stack.withEntry(stack.newBindingStackEntry(depKey, this, roots)) {
              val typeKey = depKey.typeKey
              if (typeKey !in bindings) {
                // If the binding isn't present, we'll report it later
                computeBinding(depKey)?.let { bindingQueue.addLast(it) }
              }
            }
          }
        }

        binding.visitDependencies()

        fun Binding.visitAggregatedDependencies() {
          for (binding in aggregatedBindings) {
            if (binding.typeKey !in bindings) {
              // If the binding isn't present, we'll report it later
              // TODO why can't we just add the binding directly to the queue?
              computeBinding(binding.contextualTypeKey)?.let { bindingQueue.addLast(it) }
            }
            // Queue up aggregated bindings' deps just in case
            @Suppress("UNCHECKED_CAST")
            for (depKey in (binding as Binding).dependencies) {
              if (depKey.typeKey !in bindings) {
                bindingQueue.addLast(binding)
              }
            }
          }
        }

        binding.visitAggregatedDependencies()
      }
    }
  }

  private fun checkForCyclesAndSort(
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    stack: BindingStack,
  ): List<TypeKey> {

    /*
     * Build the adjacency list we’ll feed to [topologicalSort]. – Edges that pass through a
     * deferrable wrapper (Lazy/Provider/…) are **omitted** so the remaining graph is a DAG. –
     * Aggregated‑binding edges are flattened the same way the old cacheEdges() did.
     */
    val sourceToTarget: Map<TypeKey, Set<TypeKey>> =
      bindings.mapValues { (_, binding) ->
        binding.dependencies
          .asSequence()
          .filterNot { it.isDeferrable }
          .mapToSet { it.typeKey }
      }

    val onMissing: (TypeKey, TypeKey) -> Unit = { source, missing ->
      val binding = bindings.getValue(source)
      val contextKey = binding.dependencies.first { it.typeKey == missing }
      if (!contextKey.hasDefault) {
        val stackEntry = stack.newBindingStackEntry(contextKey, binding, roots)

        // If there's a root entry for the missing binding, add it into the stack too
        val matchingRootEntry =
          roots.entries.firstOrNull { it.key.typeKey == binding.typeKey }?.value
        matchingRootEntry?.let { stack.push(it) }
        stack.withEntry(stackEntry) { reportMissingBinding(missing, stack) }
      }
    }

    /**
     * Run topo sort. It gives back either a valid order or calls onCycle/onMissing for errors
     *
     * Note that onMissing will gracefully
     */
    val result =
      bindings.keys.topologicalSort(
        sourceToTarget = { k -> sourceToTarget[k].orEmpty() },
        errorHandler = BindingGraphErrorHandler(onMissing) { cycle ->
          // Populate the BindingStack for a readable cycle trace
          val entriesInCycle =
            cycle
              .mapIndexed { i, key ->
                val callingBinding =
                  if (i == 0) {
                    // This is the first index, must be an entry-point instead (i.e. "requested by")
                    null
                  } else {
                    bindings.getValue(cycle[i - 1])
                  }
                stack.newBindingStackEntry(
                  callingBinding?.dependencies?.firstOrNull { it.typeKey == key }
                    ?: bindings.getValue(key).contextualTypeKey,
                  callingBinding,
                  roots,
                )
              }
              .reversed()
          reportCycle(entriesInCycle, stack)
        },
        onMissing = onMissing,
      )

    return result // guaranteed size == V, no cycles
  }

  private fun reportCycle(fullCycle: List<BindingStackEntry>, stack: BindingStack): Nothing {
    val message = buildString {
      appendLine(
        "[Metro/DependencyCycle] Found a dependency cycle while processing '${stack.graphFqName.asString()}'."
      )
      // Print a simple diagram of the cycle first
      val indent = "    "
      appendLine("Cycle:")
      if (fullCycle.size == 2) {
        val key = fullCycle[0].contextKey.typeKey
        append(
          "$indent${key.render(short = true)} <--> ${key.render(short = true)} (depends on itself)"
        )
      } else {
        fullCycle.joinTo(this, separator = " --> ", prefix = indent) {
          it.contextKey.render(short = true)
        }
      }

      appendLine()
      appendLine()
      // Print the full stack
      appendLine("Trace:")
      appendBindingStackEntries(
        stack.graphFqName,
        fullCycle,
        indent = indent,
        ellipse = fullCycle.size > 1,
        short = false,
      )
    }
    onError(message, stack)
  }

  override val snapshot: Map<TypeKey, Binding>
    get() = bindings

  fun replace(binding: Binding) {
    bindings[binding.typeKey] = binding
  }

  /**
   * @param key The key to put the binding under. Can be customized to link/alias a key to another
   *   binding
   */
  fun tryPut(binding: Binding, bindingStack: BindingStack, key: TypeKey = binding.typeKey) {
    check(!sealed) { "Graph already sealed" }
    if (binding.isTransient) {
      // Absent binding or otherwise not something we store
      return
    }
    if (bindings.containsKey(key)) {
      val message = buildString {
        appendLine(
          "[Metro/DuplicateBinding] Duplicate binding for ${key.render(short = false, includeQualifier = true)}"
        )
        val existing = bindings.getValue(key)
        val duplicate = binding
        appendLine("├─ Binding 1: ${existing.renderLocationDiagnostic()}")
        appendLine("├─ Binding 2: ${duplicate.renderLocationDiagnostic()}")
        if (existing === duplicate) {
          appendLine("├─ Bindings are the same: $existing")
        } else if (existing == duplicate) {
          appendLine("├─ Bindings are equal: $existing")
        }
        appendBindingStack(bindingStack)
      }
      onError(message, bindingStack)
    }
    bindings[binding.typeKey] = binding
  }

  override operator fun get(key: TypeKey): Binding? = bindings[key]

  override operator fun contains(key: TypeKey): Boolean = bindings.containsKey(key)

  // O(1) after seal()
  override fun TypeKey.dependsOn(other: TypeKey): Boolean {
    return bindingIndices.getValue(this) >= bindingIndices.getValue(other)
  }

  fun getOrCreateBinding(contextKey: ContextualTypeKey, stack: BindingStack): Binding {
    return bindings[contextKey.typeKey]
      ?: createBindingOrFail(contextKey, stack).also { tryPut(it, stack) }
  }

  fun createBindingOrFail(contextKey: ContextualTypeKey, stack: BindingStack): Binding {
    return computeBinding(contextKey) ?: reportMissingBinding(contextKey.typeKey, stack)
  }

  fun requireBinding(contextKey: ContextualTypeKey, stack: BindingStack): Binding {
    return bindings[contextKey.typeKey]
      ?: contextKey.takeIf { it.hasDefault }?.let { absentBinding(it.typeKey) }
      ?: reportMissingBinding(contextKey.typeKey, stack)
  }

  fun reportMissingBinding(
    typeKey: TypeKey,
    bindingStack: BindingStack,
    extraContent: StringBuilder.() -> Unit = {},
  ): Nothing {
    val message = buildString {
      append(
        "[Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: "
      )
      appendLine(typeKey.render(short = false))
      appendLine()
      appendBindingStack(bindingStack, short = false)
      val similarBindings = findSimilarBindings(typeKey)
      if (similarBindings.isNotEmpty()) {
        appendLine()
        appendLine("Similar bindings:")
        similarBindings.values.map { "  - $it" }.sorted().forEach(::appendLine)
      }
      extraContent()
    }

    onError(message, bindingStack)
  }
}
