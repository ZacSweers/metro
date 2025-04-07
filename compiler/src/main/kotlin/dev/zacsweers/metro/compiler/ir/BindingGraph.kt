// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.mapToSet
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.util.kotlinFqName

// TODO would be great if this was standalone to more easily test.
internal class BindingGraph(private val metroContext: IrMetroContext) {
  // Use ConcurrentHashMap to allow reentrant modification
  private val bindings = ConcurrentHashMap<TypeKey, Binding>()
  private val dependencies = ConcurrentHashMap<TypeKey, Lazy<Set<ContextualTypeKey>>>()
  private val accessors = mutableMapOf<ContextualTypeKey, BindingStack.Entry>()
  private val injectors = mutableMapOf<TypeKey, BindingStack.Entry>()

  // Thin immutable view over the internal bindings
  fun bindingsSnapshot(): Map<TypeKey, Binding> = bindings

  fun addAccessor(key: ContextualTypeKey, entry: BindingStack.Entry) {
    accessors[key] = entry
  }

  fun addInjector(key: TypeKey, entry: BindingStack.Entry) {
    injectors[key] = entry
  }

  fun addBinding(key: TypeKey, binding: Binding, bindingStack: BindingStack) {
    if (binding is Binding.Absent) {
      // Don't store absent bindings
      return
    }
    if (bindings.containsKey(key)) {
      val message = buildString {
        appendLine(
          "[Metro/DuplicateBinding] Duplicate binding for ${key.render(short = false, includeQualifier = true)}"
        )
        val existing = bindings.getValue(key)
        val duplicate = binding
        appendLine("├─ Binding 1: ${existing.getContributionLocationOrDiagnosticInfo()}")
        appendLine("├─ Binding 2: ${duplicate.getContributionLocationOrDiagnosticInfo()}")
        if (existing == duplicate) {
          appendLine("├─ Bindings are equal ${bindingStack.graph.name}")
          appendLine(Throwable().stackTraceToString().lineSequence().joinToString { "\n├─ $it" })
        }
        if (existing === duplicate) {
          appendLine("├─ Bindings are the same: ${bindingStack.graph.name}")
        }
        appendBindingStack(bindingStack)
      }
      val location = bindingStack.graph.location()
      metroContext.reportError(message, location)
      exitProcessing()
    }
    bindings[key] = binding

    if (binding is Binding.BoundInstance) {
      // No dependencies to store
      return
    }

    // Lazily evaluate dependencies so that we get a shallow set of keys
    // upfront but can defer resolution of bindings from dependencies until
    // the full graph is actualized.
    // Otherwise, this scenario wouldn't work in this order:
    //
    // val charSequenceValue: CharSequence
    // @Provides private fun bind(stringValue: String): CharSequence = this
    // @Provides private fun provideString(): String = "Hi"
    //
    // Because it would try to eagerly look up bindings for String but String
    // hadn't been encountered yet.
    // TODO would this possibly deadlock in a cycle? Need reentrancy checks
    dependencies[key] = lazy {
      when (binding) {
        is Binding.ConstructorInjected -> {
          // Recursively follow deps from its constructor params
          getConstructorDependencies(bindingStack, binding.injectedConstructor)
        }
        is Binding.Alias -> {
          setOf(ContextualTypeKey(binding.aliasedType))
        }
        is Binding.Provided -> {
          getFunctionDependencies(binding.providerFactory.providesFunction, bindingStack)
        }
        is Binding.Assisted -> {
          val targetConstructor = binding.target.injectedConstructor
          getConstructorDependencies(bindingStack, targetConstructor)
        }
        is Binding.Multibinding -> {
          // This is a manual @Multibinds or triggered by the above
          // This type's dependencies are just its providers' dependencies
          // TODO dedupe logic with above
          binding.sourceBindings.flatMapTo(mutableSetOf()) { sourceBinding ->
            when (sourceBinding) {
              is Binding.Provided -> {
                sourceBinding.parameters.valueParameters.mapToSet { it.contextualTypeKey }
              }
              is Binding.Alias -> {
                setOf(ContextualTypeKey(sourceBinding.aliasedType))
              }
              else -> error("Not possible")
            }
          }
        }
        is Binding.MembersInjected -> {
          binding.parameters.valueParameters.mapToSet { it.contextualTypeKey }
        }
        is Binding.ObjectClass,
        is Binding.BoundInstance,
        is Binding.GraphDependency -> emptySet()
        is Binding.Absent -> error("Should never happen")
      }
    }
  }

  fun findBinding(key: TypeKey): Binding? = bindings[key]

  // For bindings we expect to already be cached
  fun requireBinding(key: TypeKey, stack: BindingStack): Binding =
    bindings[key]
      ?: run {
        stack.push(
          BindingStack.Entry.simpleTypeRef(
            ContextualTypeKey(
              key,
              isWrappedInProvider = false,
              isWrappedInLazy = false,
              isLazyWrappedInProvider = false,
              hasDefault = false,
            )
          )
        )
        val message = buildString {
          appendLine("No binding found for $key")
          appendBindingStack(stack)
          if (metroContext.debug) {
            appendLine(dumpGraph(stack.graph.kotlinFqName.asString(), short = false))
          }
        }
        metroContext.reportError(message, stack.lastEntryOrGraph.location())
        exitProcessing()
      }

  fun getOrCreateMultibinding(
    pluginContext: IrPluginContext,
    typeKey: TypeKey,
    bindingStack: BindingStack,
  ): Binding.Multibinding {
    val binding =
      bindings.getOrPut(typeKey) {
        Binding.Multibinding.create(metroContext, typeKey, null).also {
          addBinding(typeKey, it, bindingStack)
          // If it's a map, expose a binding for Map<KeyType, Provider<ValueType>>
          if (it.isMap) {
            val keyType = (typeKey.type as IrSimpleType).arguments[0].typeOrNull!!
            val valueType =
              typeKey.type.arguments[1]
                .typeOrNull!!
                .wrapInProvider(this@BindingGraph.metroContext.symbols.metroProvider)
            val providerTypeKey =
              typeKey.copy(type = pluginContext.irBuiltIns.mapClass.typeWith(keyType, valueType))
            addBinding(providerTypeKey, it, bindingStack)
          }
        }
      }

    return binding as? Binding.Multibinding
      ?: error(
        """
        Expected a multibinding but got $binding.
      """
          .trimIndent()
      )
  }

  fun getOrCreateBinding(contextKey: ContextualTypeKey, bindingStack: BindingStack): Binding {
    val key = contextKey.typeKey
    val existingBinding = bindings[key]
    if (existingBinding != null) {
      return existingBinding
    }

    val binding = metroContext.injectedClassBindingOrNull(contextKey, bindingStack, this)
    when (binding) {
      is Binding.Absent -> {
        // Do nothing, don't store this
      }
      is Binding -> {
        addBinding(key, binding, bindingStack)
      }
      null -> reportMissingBinding(key, bindingStack)
    }
    return binding
  }

  operator fun contains(key: TypeKey): Boolean = bindings.containsKey(key)

  fun validate(node: DependencyGraphNode, onError: (String) -> Nothing): Set<TypeKey> {
    val deferredTypes = checkCycles(node, onError)
    checkMissingDependencies(onError)
    return deferredTypes
  }

  private fun checkCycles(node: DependencyGraphNode, onError: (String) -> Nothing): Set<TypeKey> {
    val visited = mutableSetOf<TypeKey>()
    val stackLogger = metroContext.loggerFor(MetroLogger.Type.CycleDetection)
    val stack = BindingStack(node.sourceGraph, stackLogger)
    val deferredTypes = mutableSetOf<TypeKey>()

    fun dfs(binding: Binding, contextKey: ContextualTypeKey = binding.contextualTypeKey) {
      if (binding is Binding.Absent || binding is Binding.BoundInstance) return

      if (binding is Binding.Assisted) {
        // TODO add another synthetic entry here pointing at the assisted factory type?
        return dfs(binding.target, contextKey)
      }

      val key = contextKey.typeKey
      val entriesInCycle = stack.entriesSince(key)
      if (entriesInCycle.isNotEmpty()) {
        // Check if there's a deferrable type in the stack, if so we can break the cycle
        // A -> B -> Lazy<A> is valid
        // A -> B -> A is not
        val isATrueCycle =
          key !in deferredTypes &&
            !contextKey.isDeferrable &&
            entriesInCycle.none { it.contextKey.isDeferrable }
        if (isATrueCycle) {
          // Pull the root entry from the stack and add it back to the bottom of the stack to
          // highlight the cycle
          val fullCycle = entriesInCycle + entriesInCycle[0]

          val message = buildString {
            appendLine(
              "[Metro/DependencyCycle] Found a dependency cycle while processing '${node.sourceGraph.kotlinFqName}'."
            )
            // Print a simple diagram of the cycle first
            val indent = "    "
            appendLine("Cycle:")
            // If the cycle is just the same binding pointing at itself, can make that a bit more
            // explicit with the arrow
            val separator = if (fullCycle.size == 2) " <--> " else " --> "
            fullCycle.joinTo(this, separator = separator, prefix = indent) {
              it.contextKey.render(short = true)
            }

            appendLine()
            appendLine()

            // Print the full stack
            appendLine("Trace:")
            appendBindingStackEntries(
              stack.graph.kotlinFqName,
              fullCycle,
              indent = indent,
              ellipse = true,
              short = false,
            )
          }
          onError(message)
        } else if (!contextKey.isIntoMultibinding) {
          // TODO this if check isn't great
          stackLogger.log("Deferring ${key.render(short = true)}")
          deferredTypes += key
          // We're in a loop here so nothing else needed
          return
        } else {
          // Proceed
        }
      }

      if (key in visited) {
        stackLogger.log("✅ ${key.render(short = true)} previously visited, skipping...")
        return
      }

      visited += key

      dependencies[key]?.value?.forEach { contextDep ->
        stackLogger.log(
          "Visiting dependency ${contextDep.typeKey.render(short = true)} from ${key.render(short = true)}"
        )
        val dep = contextDep.typeKey
        val dependencyBinding = getOrCreateBinding(contextDep, stack)
        val nextEntry =
          bindingStackEntryForDependency(
            binding = binding,
            contextKey = contextKey,
            targetKey = dep,
          )
        stack.withEntry(nextEntry) { dfs(dependencyBinding, contextDep) }
      }
    }

    for ((key, entry) in accessors) {
      stackLogger.log("Traversing from accessor root: ${key.typeKey}")
      stack.withEntry(entry) {
        val binding = getOrCreateBinding(key, stack)
        dfs(binding)
      }
      stackLogger.log("End traversing from accessor root: ${key.typeKey}")
    }

    for ((key, entry) in injectors) {
      stackLogger.log("Traversing from injector root: $key")
      stack.withEntry(entry) {
        val binding = getOrCreateBinding(ContextualTypeKey(key), stack)
        dfs(binding)
      }
      stackLogger.log("End traversing from injector root: $key")
    }
    stackLogger.log(
      "End validation of ${node.sourceGraph.kotlinFqName.asString()}. Deferred types are $deferredTypes"
    )
    return deferredTypes
  }

  private fun checkMissingDependencies(onError: (String) -> Nothing) {
    val allDeps = dependencies.values.map { it.value }.flatten().mapToSet { it.typeKey }
    val missing = allDeps - bindings.keys
    if (missing.isNotEmpty()) {
      onError("Missing bindings for: $missing")
    }
  }

  private fun getConstructorDependencies(
    bindingStack: BindingStack,
    constructor: IrConstructor,
  ): Set<ContextualTypeKey> {
    return getFunctionDependencies(constructor, bindingStack)
  }

  private fun getFunctionDependencies(
    function: IrFunction,
    bindingStack: BindingStack,
  ): Set<ContextualTypeKey> {
    val parameters = buildList {
      if (function !is IrConstructor) {
        function.extensionReceiverParameter?.let(::add)
      }
      addAll(function.valueParameters)
    }
    return parameters
      .filterNot {
        it.isAnnotatedWithAny(this@BindingGraph.metroContext.symbols.assistedAnnotations)
      }
      .mapNotNull { param ->
        val paramKey = ContextualTypeKey.from(metroContext, param)
        val binding =
          bindingStack.withEntry(BindingStack.Entry.injectedAt(paramKey, function, param)) {
            // This recursive call will create bindings for injectable types as needed
            getOrCreateBinding(paramKey, bindingStack)
          }
        if (binding is Binding.Absent) {
          // Skip this key as it's absent
          return@mapNotNull null
        }
        paramKey
      }
      .toSet()
  }

  // TODO iterate on this more!
  internal fun dumpGraph(name: String, short: Boolean): String {
    if (bindings.isEmpty()) return "Empty binding graph"

    return buildString {
      appendLine("Binding Graph: $name")
      // Sort by type key for consistent output
      bindings.entries
        .sortedBy { it.key.toString() }
        .forEach { (_, binding) ->
          appendLine("─".repeat(50))
          appendBinding(binding, short, isNested = false)
        }
    }
  }

  private fun reportMissingBinding(typeKey: TypeKey, bindingStack: BindingStack): Nothing {
    val declarationToReport = bindingStack.lastEntryOrGraph
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
        similarBindings.map { "  - $it" }.sorted().forEach(::appendLine)
      }
      if (metroContext.debug) {
        appendLine(dumpGraph(bindingStack.graph.kotlinFqName.asString(), short = false))
      }
    }

    with(metroContext) { declarationToReport.reportError(message) }

    exitProcessing()
  }

  // TODO
  //  - exclude types _in_ multibindings
  //  - same type with diff nullability
  private fun findSimilarBindings(key: TypeKey): List<SimilarBinding> {
    val similarBindings = mutableListOf<SimilarBinding>()

    // Same type with different qualifier
    if (key.qualifier != null) {
      findBinding(key.copy(qualifier = null))?.let {
        similarBindings.add(SimilarBinding(it, "Different qualifier"))
      }
    }

    // Little more involved, iterate the bindings for ones with the same type
    bindings.forEach { (bindingKey, binding) ->
      when {
        key.qualifier == null && bindingKey.type == key.type -> {
          similarBindings.add(SimilarBinding(binding, "Different qualifier"))
        }
        binding is Binding.Multibinding -> {
          val valueType =
            if (binding.isSet) {
              (bindingKey.type.type as IrSimpleType).arguments[0].typeOrFail
            } else {
              // Map binding
              (bindingKey.type.type as IrSimpleType).arguments[1].typeOrFail
            }
          if (valueType == key.type) {
            similarBindings.add(SimilarBinding(binding, "Multibinding"))
          }
        }
        bindingKey.type == key.type -> {
          // Already covered above but here to avoid falling through to the subtype checks
          // below as they would always return true for this
        }
        bindingKey.type.isSubtypeOf(key.type, metroContext.irTypeSystemContext) -> {
          similarBindings.add(SimilarBinding(binding, "Subtype"))
        }
        key.type.type.isSubtypeOf(bindingKey.type, metroContext.irTypeSystemContext) -> {
          similarBindings.add(SimilarBinding(binding, "Supertype"))
        }
      }
    }

    // TODO filter out source bindings in multibindings? Should be covered though
    return similarBindings
  }

  private fun Appendable.appendBinding(binding: Binding, short: Boolean, isNested: Boolean) {
    appendLine("Type: ${binding.typeKey.render(short)}")
    appendLine("├─ Binding: ${binding::class.simpleName}")
    appendLine("├─ Contextual Type: ${binding.contextualTypeKey.render(short)}")

    binding.scope?.let { scope -> appendLine("├─ Scope: $scope") }

    if (binding is Binding.Alias) {
      appendLine("├─ Aliased type: ${binding.aliasedType.render(short)}")
    }

    if (binding.dependencies.isNotEmpty()) {
      appendLine("├─ Dependencies:")
      binding.dependencies.forEach { (depKey, param) ->
        appendLine("│  ├─ ${depKey.render(short)}")
        appendLine("│  │  └─ Parameter: ${param.name} (${param.contextualTypeKey.render(short)})")
      }
    }

    if (binding.parameters.allParameters.isNotEmpty()) {
      appendLine("├─ Parameters:")
      binding.parameters.allParameters.forEach { param ->
        appendLine("│  └─ ${param.name}: ${param.contextualTypeKey.render(short)}")
      }
    }

    if (!isNested && binding is Binding.Multibinding && binding.sourceBindings.isNotEmpty()) {
      appendLine("├─ Source bindings:")
      binding.sourceBindings.forEach { sourceBinding ->
        val nested = buildString { appendBinding(sourceBinding, short, isNested = true) }
        append("│  ├─ ")
        appendLine(nested.lines().first())
        appendLine(nested.lines().drop(1).joinToString("\n").prependIndent("│  │  "))
      }
    }

    binding.reportableLocation?.let { location -> appendLine("└─ Location: ${location.render()}") }
  }

  data class SimilarBinding(val binding: Binding, val description: String) {
    override fun toString(): String {
      return buildString {
        append(binding.typeKey.render(short = true))
        append(" (")
        append(description)
        append("). Type: ")
        append(binding.javaClass.simpleName)
        append('.')
        binding.reportableLocation?.render()?.let {
          append(" Source: ")
          append(it)
        }
      }
    }
  }
}
