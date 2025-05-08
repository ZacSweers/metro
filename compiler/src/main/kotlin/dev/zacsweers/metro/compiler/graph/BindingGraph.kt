// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.ir.appendBindingStack
import dev.zacsweers.metro.compiler.ir.appendBindingStackEntries
import dev.zacsweers.metro.compiler.ir.withEntry
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
    BindingStack.(contextKey: ContextualTypeKey, callingBinding: Binding) -> BindingStackEntry,
  private val absentBinding: (typeKey: TypeKey) -> Binding,
  /**
   * Creates a binding for keys not necessarily manually added to the graph (e.g.,
   * constructor-injected types).
   */
  private val computeBinding: (contextKey: ContextualTypeKey, stack: BindingStack) -> Binding? =
    { _, _ ->
      null
    },
  private val onError: (String, BindingStack) -> Nothing = { message, stack -> error(message) },
  private val findSimilarBindings: (key: TypeKey) -> Map<TypeKey, String> = { emptyMap() },
  private val stackLogger: MetroLogger = MetroLogger.NONE,
) : BindingGraph<Type, TypeKey, ContextualTypeKey, Binding, BindingStackEntry, BindingStack> {
  // Populated by initial graph setup and later seal()
  private val bindings = mutableMapOf<TypeKey, Binding>()
  // Populated by seal()
  private val transitive = hashMapOf<TypeKey, Set<TypeKey>>()

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
  ): Set<TypeKey> {
    val stack = newBindingStack()

    populateGraph(roots, stack, tracer)

    checkForCycles(roots, stack, tracer)

    cacheEdges(tracer)

    sealed = true
    return deferredTypes
  }

  private fun populateGraph(
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    stack: BindingStack,
    tracer: Tracer,
  ) {
    // Traverse all the bindings up front to
    // First ensure all the roots' bindings are present
    for (contextKey in roots.keys) {
      computeBinding(contextKey, stack)?.let { tryPut(it, stack, contextKey.typeKey) }
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
            stack.withEntry(stack.newBindingStackEntry(depKey, this)) {
              val typeKey = depKey.typeKey
              if (typeKey !in bindings) {
                // If the binding isn't present, we'll report it later
                computeBinding(depKey, stack)?.let { bindingQueue.addLast(it) }
              }
            }
          }
        }

        binding.visitDependencies()

        fun Binding.visitAggregatedDependencies() {
          for (binding in aggregatedBindings) {
            if (binding.typeKey !in bindings) {
              // If the binding isn't present, we'll report it later
              computeBinding(binding.contextualTypeKey, stack)?.let { bindingQueue.addLast(it) }
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

  private fun checkForCycles(
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    stack: BindingStack,
    tracer: Tracer,
  ) {
    fun reportCycle(fullCycle: List<BindingStackEntry>): Nothing {
      val message = buildString {
        appendLine(
          "[Metro/DependencyCycle] Found a dependency cycle while processing '${stack.graphFqName.asString()}'."
        )
        // Print a simple diagram of the cycle first
        val indent = "    "
        appendLine("Cycle:")
        if (fullCycle.size == 1) {
          val key = fullCycle[0].contextKey.typeKey
          append(
            "$indent${key.render(short = true)} <--> ${key.render(short = true)} (depends on itself)"
          )
        } else {
          // If the cycle is just the same binding pointing at itself, can make that a bit more
          // explicit with the arrow
          val separator = if (fullCycle.size == 2) " <--> " else " --> "
          fullCycle.joinTo(this, separator = separator, prefix = indent) {
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

    /* 1. reject strict cycles / missing bindings */
    fun dfsStrict(binding: Binding, contextKey: ContextualTypeKey) {
      stackLogger.log(
        "DFS: ${binding.typeKey} ($contextKey). Stack: ${stack.entries.drop(1).joinToString { it.typeKey.render(short = true) }}"
      )

      if (binding.isTransient) {
        // Absent binding or otherwise not something we store
        return
      }

      val key = binding.typeKey
      val cycle = stack.entriesSince(key)
      if (cycle.isNotEmpty()) {
        stackLogger.log("-> Cycle! ${cycle.joinToString { it.typeKey.render(short = true) }}")
        // Check if there's a deferrable type in the stack, if so we can break the cycle
        // A -> B -> Lazy<A> is valid
        // A -> B -> A is not
        val isTrueCycle =
          key !in deferredTypes &&
            !contextKey.isDeferrable &&
            cycle.none { it.contextKey.isDeferrable }
        if (contextKey.isIntoMultibinding) {
          // Proceed
          stackLogger.log("--> Into multibinding, proceeding")
        } else if (isTrueCycle) {
          stackLogger.log("--> ❌True cycle!")
          // Pull the root entry from the stack and add it back to the bottom of the stack to
          // highlight the cycle
          val fullCycle = cycle + cycle[0]
          reportCycle(fullCycle)
        } else {
          // TODO this if check isn't great
          stackLogger.log("--> Deferring ${key.render(short = true)}")
          deferredTypes += key
          // We're in a loop here so nothing else needed
          return
        }
      }

      stackLogger.log("--> Traversing dependencies")
      for (depKey in binding.dependencies) {
        stackLogger.log("----> Dependency: ${depKey.render(short = true)}")
        val stackEntry = stack.newBindingStackEntry(depKey, binding)
        stack.withEntry(stackEntry) {
          val depBinding = requireBinding(depKey, stack)
          stackLogger.log("----> Binding: $depBinding")
          // Check direct dependencies for cycles
          if (depBinding == binding && contextKey == depKey && !depKey.isDeferrable) {
            stackLogger.log(
              "----> ❌Found a direct cycle! ${stackEntry.typeKey.render(short = true)}"
            )
            reportCycle(listOf(stackEntry))
          } else {
            stackLogger.log("└─-----> Recursing ${key.render(short = true)}")
            dfsStrict(depBinding, depKey)
          }
        }
      }

      stackLogger.log("--> Traversing aggregatedBindings")
      for (depBinding in binding.aggregatedBindings) {
        stackLogger.log("----> Binding: $depBinding")
        val stackEntry = stack.newBindingStackEntry(depBinding.contextualTypeKey, binding)
        stack.withEntry(stackEntry) {
          stackLogger.log("----> Binding: $depBinding")
          stackLogger.log("└─-----> Recursing ${key.render(short = true)}")
          @Suppress("UNCHECKED_CAST") dfsStrict(depBinding as Binding, depBinding.contextualTypeKey)
        }
      }

      stackLogger.log("--> Exit DFS: ${key.render(short = true)}")
    }

    // Track strict visits
    val strictVisits = hashSetOf<TypeKey>()

    // Walk from roots first
    tracer.traceNested("Traverse from roots") {
      for ((contextKey, entry) in roots) {
        stackLogger.log("Traversing root: ${contextKey.render(short = true)}")
        stack.withEntry(entry) {
          val binding = requireBinding(contextKey, stack)
          stackLogger.log("Root binding: $binding")
          dfsStrict(binding, contextKey)
          strictVisits += contextKey.typeKey
        }
      }
    }

    // Validate remaining bindings
    tracer.traceNested("Traverse remaining bindings") {
      for (binding in bindings.values) {
        if (binding.typeKey in strictVisits) continue

        dfsStrict(binding, binding.contextualTypeKey)
      }
    }
  }

  private fun cacheEdges(tracer: Tracer) {
    val visiting = mutableSetOf<TypeKey>()

    /* 2. cache transitive closure (all edges) */
    fun dfsAll(key: TypeKey): Set<TypeKey> {
      // Bounce if it's already cached
      transitive[key]?.let {
        return it
      }

      // Bounce if it's a strict cycle. We already validated these above
      if (!visiting.add(key)) return emptySet()

      // Compute transitive deps.
      // Important to do this in a local var rather than a getOrPut() call to avoid a reentrant
      // update
      val binding = bindings[key]
      val directDepKeys =
        binding
          ?.dependencies
          .orEmpty()
          .plus(binding?.aggregatedBindings.orEmpty().map { it.contextualTypeKey })
      val deps =
        directDepKeys.asSequence().flatMapToSet {
          sequence {
            yield(it.typeKey)
            yieldAll(dfsAll(it.typeKey))
          }
        }

      visiting.remove(key)

      // Memoize *after* computation
      transitive[key] = deps
      return deps
    }

    tracer.traceNested("Cache transitive closure") { bindings.keys.forEach(::dfsAll) }
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
  override fun TypeKey.dependsOn(other: TypeKey): Boolean =
    transitive[this]?.contains(other) == true

  fun getOrCreateBinding(contextKey: ContextualTypeKey, stack: BindingStack): Binding {
    return bindings[contextKey.typeKey]
      ?: createBindingOrFail(contextKey, stack).also { tryPut(it, stack) }
  }

  fun createBindingOrFail(contextKey: ContextualTypeKey, stack: BindingStack): Binding {
    return computeBinding(contextKey, stack) ?: reportMissingBinding(contextKey.typeKey, stack)
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
