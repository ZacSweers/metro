// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.ir.graph.appendBindingStack
import dev.zacsweers.metro.compiler.ir.graph.appendBindingStackEntries
import dev.zacsweers.metro.compiler.ir.graph.withEntry
import dev.zacsweers.metro.compiler.joinWithDynamicSeparatorTo
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import java.util.SortedMap
import java.util.SortedSet
import java.util.TreeSet

internal interface BindingGraph<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, TypeKey>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, ContextualTypeKey>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
  BindingStackEntry : BaseBindingStack.BaseEntry<Type, TypeKey, ContextualTypeKey>,
  BindingStack : BaseBindingStack<*, Type, TypeKey, BindingStackEntry, BindingStack>,
> {
  val bindings: Map<TypeKey, Binding>

  operator fun get(key: TypeKey): Binding?

  operator fun contains(key: TypeKey): Boolean

  fun TypeKey.dependsOn(other: TypeKey): Boolean
}

// TODO instead of implementing BindingGraph, maybe just make this a builder and have build()
//  produce one?
internal open class MutableBindingGraph<
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
    (contextKey: ContextualTypeKey, currentBindings: Set<TypeKey>, stack: BindingStack) -> Set<
        Binding
      > =
    { _, _, _ ->
      emptySet()
    },
  private val onError: (String, BindingStack) -> Unit = { message, _ -> error(message) },
  private val onHardError: (String, BindingStack) -> Nothing = { message, _ -> error(message) },
  private val missingBindingHints:
    (key: TypeKey, stack: BindingStack) -> MissingBindingHints<Type, TypeKey> =
    { _, _ ->
      MissingBindingHints()
    },
) : BindingGraph<Type, TypeKey, ContextualTypeKey, Binding, BindingStackEntry, BindingStack> {
  // Populated by initial graph setup and later seal()
  override val bindings = mutableMapOf<TypeKey, Binding>()
  private val bindingIndices = mutableMapOf<TypeKey, Int>()
  private val reportedMissingKeys = mutableSetOf<TypeKey>()

  var sealed = false
    private set

  /**
   * Finalizes the binding graph by performing validation and cache initialization.
   *
   * This function operates in a two-step process:
   * 1. Validates the binding graph by performing a [topologicalSort]. Cycles that involve
   *    deferrable types, such as `Lazy` or `Provider`, are allowed and deferred for special
   *    handling at code-generation-time and store any deferred types in [deferredTypes]. Any
   *    strictly invalid cycles or missing bindings result in an error being thrown.
   * 2. The returned topologically sorted list is then processed to compute [bindingIndices] and
   *    [deferredTypes]. Any dependency whose index is later than the current index is presumed a
   *    valid cycle indicator and thus that type must be deferred.
   *
   * This operation runs in O(V+E). After calling this function, the binding graph becomes
   * immutable.
   *
   * Calls [onError] if a strict dependency cycle or missing binding is encountered during
   * validation.
   *
   * @param onPopulated a callback for when the graph is fully populated but not yet validated.
   * @param validateBindings a callback to perform optional extra validation on bindings
   *   post-adjacency build.
   * @param keep optional set of keys to keep, even if they are unused.
   */
  fun seal(
    roots: Map<ContextualTypeKey, BindingStackEntry> = emptyMap(),
    keep: Map<ContextualTypeKey, BindingStackEntry> = emptyMap(),
    shrinkUnusedBindings: Boolean = true,
    tracer: Tracer = Tracer.NONE,
    onPopulated: () -> Unit = {},
    onSortedCycle: (List<TypeKey>) -> Unit = {},
    validateBindings:
      (
        bindings: Map<TypeKey, Binding>,
        stack: BindingStack,
        roots: Map<ContextualTypeKey, BindingStackEntry>,
        adjacency: Map<TypeKey, Set<TypeKey>>,
      ) -> Unit =
      { _, _, _, _ -> /* noop */
      },
  ): GraphTopology<TypeKey> {
    val stack = newBindingStack()

    // Order matters, prefer roots over matching kees as they have more information in their entries
    val rootsWithKeeps = keep + roots
    val missingBindings = populateGraph(rootsWithKeeps, stack, tracer)

    onPopulated()

    sealed = true

    /**
     * Build the full adjacency mapping of keys to all their dependencies.
     *
     * Note that `onMissing` will gracefully allow missing targets that have default values (i.e.
     * optional bindings).
     */
    val fullAdjacency =
      tracer.traceNested("Build adjacency list") {
        buildFullAdjacency(
          bindings = bindings,
          dependenciesOf = { binding -> binding.dependencies.map { it.typeKey } },
          onMissing = { source, missing ->
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
          },
        )
      }

    // Report all missing bindings _after_ building adjacency so we can backtrace where possible
    missingBindings.forEach { (key, stack) -> reportMissingBinding(key, stack) }

    // Validate bindings
    validateBindings(bindings, stack, roots, fullAdjacency)

    val topo =
      tracer.traceNested("Sort and validate") { parentTracer ->
        val allKeeps =
          if (shrinkUnusedBindings) {
            keep.keys.mapToSet { it.typeKey }
          } else {
            fullAdjacency.keys + keep.keys.mapToSet { it.typeKey }
          }
        sortAndValidate(roots, allKeeps, fullAdjacency, stack, parentTracer, onSortedCycle)
      }

    tracer.traceNested("Compute binding indices") {
      // If it depends itself or something that comes later in the topo sort, it
      // must be deferred. This is how we handle cycles that are broken by deferrable
      // types like Provider/Lazy/...
      // O(1) “does A depend on B?”
      bindingIndices.putAll(topo.sortedKeys.withIndex().associate { it.value to it.index })
    }

    return topo
  }

  private fun populateGraph(
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    stack: BindingStack,
    tracer: Tracer,
  ): Map<TypeKey, BindingStack> {
    // Traverse all the bindings up front to
    // First ensure all the roots' bindings are present
    // Defer missing binding reporting until after we finish populating
    val missingBindings = mutableMapOf<TypeKey, BindingStack>()
    for ((contextKey, entry) in roots) {
      if (contextKey.typeKey !in bindings) {
        val bindings = computeBindings(contextKey, bindings.keys, stack)
        if (bindings.isNotEmpty()) {
          for (binding in bindings) {
            tryPut(binding, stack, binding.typeKey)
          }
        } else {
          stack.withEntry(entry) { missingBindings[contextKey.typeKey] = stack.copy() }
        }
      }
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

        for (depKey in binding.dependencies) {
          stack.withEntry(stack.newBindingStackEntry(depKey, binding, roots)) {
            val typeKey = depKey.typeKey
            if (typeKey !in bindings) {
              // If the binding isn't present, we'll report it later
              val bindings = computeBindings(depKey, bindings.keys, stack)
              if (bindings.isNotEmpty()) {
                for (binding in bindings) {
                  bindingQueue.addLast(binding)
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
    }

    return missingBindings
  }

  private fun sortAndValidate(
    roots: Map<ContextualTypeKey, BindingStackEntry>,
    keep: Set<TypeKey>,
    fullAdjacency: SortedMap<TypeKey, SortedSet<TypeKey>>,
    stack: BindingStack,
    parentTracer: Tracer,
    onSortedCycle: (List<TypeKey>) -> Unit,
  ): GraphTopology<TypeKey> {
    val sortedRootKeys =
      TreeSet<TypeKey>().apply {
        roots.keys.forEach { add(it.typeKey) }
        addAll(keep)
      }

    // Run topo sort. It gives back either a valid order or calls onCycle for errors
    val result =
      parentTracer.traceNested("Topo sort") { nestedTracer ->
        topologicalSort(
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
          onCycle = { cycle ->
            val fullCycle =
              buildList {
                  addAll(cycle)
                  add(cycle.first())
                }
                // Reverse upfront so we can backward look at dependency requests
                .reversed()
            // Populate the BindingStack for a readable cycle trace
            val entriesInCycle =
              fullCycle.mapIndexed { i, key ->
                val callingBinding =
                  if (i == 0) {
                    // This is the first index, back around to the back
                    bindings.getValue(fullCycle[fullCycle.lastIndex - 1])
                  } else {
                    bindings.getValue(fullCycle[i - 1])
                  }
                stack.newBindingStackEntry(
                  callingBinding.dependencies.firstOrNull { it.typeKey == key }
                    ?: bindings.getValue(key).contextualTypeKey,
                  callingBinding,
                  roots,
                )
              }
            reportCycle(entriesInCycle, stack)
          },
          parentTracer = nestedTracer,
          isImplicitlyDeferrable = { key -> bindings.getValue(key).isImplicitlyDeferrable },
        )
      }

    return result
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
        val singleLine = fullCycle.size < 5
        fullCycle.joinWithDynamicSeparatorTo(
          this,
          separator = { prev, _ ->
            buildString {
              if (singleLine) {
                append(' ')
              } else {
                append('\n')
                append(indent)
              }
              val prevBinding = bindings.getValue(prev.typeKey)
              if (prevBinding.isAlias) {
                append("~~>")
              } else {
                append("-->")
              }
              append(' ')
            }
          },
          prefix = indent,
        ) {
          it.contextKey.render(short = true)
        }
      }

      val entriesToReport =
        if (fullCycle.size == 2) {
          fullCycle.take(1)
        } else {
          fullCycle
        }

      appendLine()
      appendLine()
      // Print the full stack
      appendLine("Trace:")
      appendBindingStackEntries(
        stack.graphFqName,
        entriesToReport,
        indent = indent,
        ellipse = entriesToReport.size > 1,
        short = false,
      )
    }
    onHardError(message, stack)
  }

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
    if (key in bindings) {
      val existing = bindings.getValue(key)
      val duplicate = binding
      reportDuplicateBinding(key, existing, duplicate, bindingStack)
    } else {
      bindings[binding.typeKey] = binding
    }
  }

  fun reportDuplicateBinding(
    key: TypeKey,
    existing: Binding,
    duplicate: Binding,
    bindingStack: BindingStack,
  ) {
    reportDuplicateBinding(
      key,
      existing.renderLocationDiagnostic(),
      duplicate.renderLocationDiagnostic(),
      bindingStack,
    ) {
      if (existing === duplicate) {
        appendLine()
        appendLine("(Hint) Bindings are the same")
      } else if (existing == duplicate) {
        appendLine()
        appendLine("(Hint) Bindings are equal")
      }
    }
  }

  fun reportDuplicateBinding(
    key: TypeKey,
    location1: LocationDiagnostic,
    location2: LocationDiagnostic,
    bindingStack: BindingStack,
    extraContent: StringBuilder.() -> Unit = {},
  ) {
    val message = buildString {
      appendLine(
        "[Metro/DuplicateBinding] Multiple bindings found for ${key.render(short = false, includeQualifier = true)}"
      )
      appendLine()
      appendLine("  ${location1.location}")
      location1.description?.let { appendLine(it.prependIndent("    ")) }
      appendLine("  ${location2.location}")
      location2.description?.let { appendLine(it.prependIndent("    ")) }
      extraContent()
      appendBindingStack(bindingStack)
    }
    onError(message, bindingStack)
  }

  override operator fun get(key: TypeKey): Binding? = bindings[key]

  override operator fun contains(key: TypeKey): Boolean = bindings.containsKey(key)

  // O(1) after seal()
  override fun TypeKey.dependsOn(other: TypeKey): Boolean {
    return bindingIndices.getValue(this) >= bindingIndices.getValue(other)
  }

  fun reportMissingBinding(
    typeKey: TypeKey,
    bindingStack: BindingStack,
    extraContent: StringBuilder.() -> Unit = {},
  ) {
    if (reportedMissingKeys.add(typeKey)) {
      val message = buildString {
        append(
          "[Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: "
        )
        appendLine(typeKey.render(short = false))
        appendLine()
        appendBindingStack(bindingStack, short = false)
        val hints = missingBindingHints(typeKey, bindingStack)
        val messages = hints.messages
        val similarBindings = hints.similarBindings

        if (messages.isNotEmpty() || similarBindings.isNotEmpty()) {
          if (messages.isNotEmpty()) {
            appendLine()
            appendLine("(Hint)")
            messages.joinTo(this, separator = "\n\n")
          }
          if (similarBindings.isNotEmpty()) {
            appendLine()
            appendLine("Similar bindings:")
            similarBindings.values.map { "  - $it" }.sorted().forEach(::appendLine)
          }
        }

        extraContent()
      }

      onError(message, bindingStack)
    }
  }
}
