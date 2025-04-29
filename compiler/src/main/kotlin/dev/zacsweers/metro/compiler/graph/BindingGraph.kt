package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.ir.appendBindingStack
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.plusAssign

internal open class BindingGraph<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, *>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
  BindingStackEntry : BaseBindingStack.BaseEntry<Type, TypeKey, ContextualTypeKey>,
  BindingStack : BaseBindingStack<*, Type, TypeKey, BindingStackEntry>,
>(
  private val newBindingStack: () -> BindingStack,
  private val newBindingStackEntry: BindingStack.(binding: Binding) -> BindingStackEntry,
  /**
   * Creates a binding for keys not necessarily manually added to the graph (e.g.,
   * constructor-injected types).
   */
  private val computeBinding: (key: TypeKey) -> Binding? = { null },
) {
  /* ConcurrentHashMaps for reentrant modifications */
  // Populated by initial graph setup and later seal()
  private val bindings = ConcurrentHashMap<TypeKey, Binding>()
  // Populated by seal()
  private val transitive = ConcurrentHashMap<TypeKey, Set<TypeKey>>()
  private val _deferredTypes = mutableSetOf<TypeKey>()

  val deferredTypes: Set<TypeKey>
    get() = _deferredTypes

  var sealed = false
    private set

  fun put(binding: Binding) {
    check(!sealed) { "Graph already sealed" }
    bindings[binding.typeKey] = binding
  }

  operator fun get(key: TypeKey): Binding? = bindings[key]

  operator fun contains(key: TypeKey): Boolean = bindings.containsKey(key)

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
   * Throws:
   * - An error if a strict dependency cycle or missing binding is encountered during validation.
   */
  fun seal(onError: (String) -> Nothing = { error(it) }) {
    val stack = newBindingStack()
    val visiting = hashSetOf<TypeKey>()

    /* 1. reject strict cycles / missing bindings */
    fun dfsStrict(binding: Binding, contextKey: ContextualTypeKey) {
      val key = binding.typeKey
      val cycle = stack.entriesSince(key)
      if (cycle.isNotEmpty()) {
        // Check if there's a deferrable type in the stack, if so we can break the cycle
        // A -> B -> Lazy<A> is valid
        // A -> B -> A is not
        val isTrueCycle =
          key !in deferredTypes &&
          !contextKey.isDeferrable && cycle.none { it.contextKey.isDeferrable }
        if (isTrueCycle) {
          // TODO port messaging
          onError("Strict dependency cycle:\n" + (cycle + cycle.first()).formatPath())
        } else if (!contextKey.isIntoMultibinding) {
          // TODO this if check isn't great
//            stackLogger.log("Deferring ${key.render(short = true)}")
          _deferredTypes += key
          // We're in a loop here so nothing else needed
          return
        } else {
          // Proceed
        }
      }

      if (!visiting.add(key)) return

      val binding = getOrCreateBinding(key, stack, onError)
      // TODO pass this in?
      stack.push(stack.newBindingStackEntry(binding))

      binding.dependencies
        .forEach {
          dfsStrict(getOrCreateBinding(it.typeKey, stack, onError), it)
        }

      stack.pop()
      visiting.remove(key)
    }
    bindings.values.forEach { binding -> dfsStrict(binding, binding.contextualTypeKey) }

    visiting.clear()

    /* 2. cache transitive closure (all edges) */
    fun dfsAll(key: TypeKey): Set<TypeKey> {
      if (!visiting.add(key)) return emptySet()
      return transitive.computeIfAbsent(key) {
        bindings[key]?.dependencies.orEmpty().flatMapToSet {
          Iterable {
            iterator {
              yield(it.typeKey)
              yieldAll(dfsAll(it.typeKey))
            }
          }
        }
      }.also { visiting.remove(key) }
    }
    bindings.keys.forEach(::dfsAll)
    sealed = true
  }

  // O(1) after seal()
  fun TypeKey.dependsOn(other: TypeKey): Boolean = transitive[this]?.contains(other) == true

  fun getOrCreateBinding(key: TypeKey, stack: BindingStack, onError: (String) -> Nothing): Binding {
    return bindings[key]
      ?: computeBinding(key)?.also { bindings[it.typeKey] = it }
      ?: onError(
        buildString {
          // TODO port messaging
          appendLine("No binding found for $key")
          appendBindingStack(stack)
        }
      )
  }
}

private fun List<BaseBindingStack.BaseEntry<*, *, *>>.formatPath(): String =
  joinToString(" -> ") { it.contextKey.typeKey.toString() }
