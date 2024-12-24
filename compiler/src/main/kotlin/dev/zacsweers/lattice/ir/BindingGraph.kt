/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.ir

import dev.zacsweers.lattice.exitProcessing
import dev.zacsweers.lattice.mapToSet
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.kotlinFqName

// TODO would be great if this was standalone to more easily test.
internal class BindingGraph(private val context: LatticeTransformerContext) {
  // Use ConcurrentHashMap to allow reentrant modification
  private val bindings = ConcurrentHashMap<TypeKey, Binding>()
  private val dependencies = ConcurrentHashMap<TypeKey, Lazy<Set<ContextualTypeKey>>>()
  // TODO eventually add inject() targets too from member injection
  private val exposedTypes = mutableMapOf<ContextualTypeKey, BindingStack.Entry>()

  fun addExposedType(key: ContextualTypeKey, entry: BindingStack.Entry) {
    exposedTypes[key] = entry
  }

  fun addBinding(key: TypeKey, binding: Binding, bindingStack: BindingStack) {
    if (binding is Binding.Absent) {
      // Don't store absent bindings
      return
    }
    if (bindings.containsKey(key)) {
      val message = buildString {
        appendLine("Duplicate binding for $key")
        if (binding is Binding.Provided) {
          appendLine(
            "Double check the provider's inferred return type or making its return type explicit."
          )
        }
        appendBindingStack(bindingStack)
      }
      val location = binding.reportableLocation ?: bindingStack.graph.location()
      context.reportError(message, location)
      exitProcessing()
    }
    require(!bindings.containsKey(key)) { "Duplicate binding for $key" }
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
          getConstructorDependencies(binding.type, bindingStack)
        }
        is Binding.Provided -> {
          getFunctionDependencies(binding.providerFunction, bindingStack)
        }
        is Binding.Assisted -> {
          getConstructorDependencies(binding.target.type, bindingStack)
        }
        is Binding.Multibinding -> {
          // This is a manual @Multibinds or triggered by the above
          // This type's dependencies are just its providers' dependencies
          binding.providers.flatMapTo(mutableSetOf()) {
            getFunctionDependencies(it.providerFunction, bindingStack)
          }
        }
        is Binding.BoundInstance -> emptySet()
        is Binding.GraphDependency -> emptySet()
        is Binding.Absent -> error("Should never happen")
      }
    }
  }

  // For bindings we expect to already be cached
  fun requireBinding(key: TypeKey): Binding =
    bindings[key]
      ?: error(
        buildString {
          appendLine("No binding found for $key")
          if (context.debug) {
            appendLine(dumpGraph(short = false))
          }
        }
      )

  fun getOrCreateMultibinding(
    pluginContext: IrPluginContext,
    typeKey: TypeKey,
  ): Binding.Multibinding {
    return bindings.getOrPut(typeKey) {
      Binding.Multibinding.create(pluginContext, typeKey).also {
        addBinding(typeKey, it, BindingStack.empty())
        // If it's a map, expose a binding for Map<KeyType, Provider<ValueType>>
        if (it.isMap) {
          val keyType = (typeKey.type as IrSimpleType).arguments[0].typeOrNull!!
          val valueType =
            typeKey.type.arguments[1].typeOrNull!!.wrapInProvider(context.symbols.latticeProvider)
          val providerTypeKey =
            TypeKey(pluginContext.irBuiltIns.mapClass.typeWith(keyType, valueType))
          addBinding(providerTypeKey, it, BindingStack.empty())
        }
      }
    } as Binding.Multibinding
  }

  fun getOrCreateBinding(contextKey: ContextualTypeKey, bindingStack: BindingStack): Binding {
    val key = contextKey.typeKey
    val existingBinding = bindings[key]
    if (existingBinding != null) {
      return existingBinding
    }

    return createBinding(contextKey, bindingStack).also { addBinding(key, it, bindingStack) }
  }

  private fun createBinding(contextKey: ContextualTypeKey, bindingStack: BindingStack): Binding {
    val key = contextKey.typeKey

    // If no explicit binding exists, check if type is injectable
    val irClass = key.type.rawType()
    val injectableConstructor = with(context) { irClass.findInjectableConstructor() }
    val binding =
      if (injectableConstructor != null) {
        val parameters = injectableConstructor.parameters(context)
        Binding.ConstructorInjected(
          type = irClass,
          injectedConstructor = injectableConstructor,
          isAssisted =
            injectableConstructor.isAnnotatedWithAny(context.symbols.assistedInjectAnnotations),
          typeKey = key,
          parameters = parameters,
          scope = with(context) { irClass.scopeAnnotation() },
        )
      } else if (with(context) { irClass.isAnnotatedWithAny(symbols.assistedFactoryAnnotations) }) {
        val function = irClass.singleAbstractFunction(context)
        val targetContextualTypeKey = ContextualTypeKey.from(context, function)
        val bindingStackEntry = BindingStack.Entry.injectedAt(contextKey, function)
        val targetBinding =
          bindingStack.withEntry(bindingStackEntry) {
            getOrCreateBinding(targetContextualTypeKey, bindingStack)
          } as Binding.ConstructorInjected
        Binding.Assisted(
          type = irClass,
          function = function,
          typeKey = key,
          parameters = function.parameters(context),
          target = targetBinding,
        )
      } else if (contextKey.hasDefault) {
        Binding.Absent(key)
      } else {
        val declarationToReport = bindingStack.lastEntryOrGraph
        val message = buildString {
          append(
            "[Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: "
          )
          appendLine(key.render(short = false))
          appendLine()
          appendBindingStack(bindingStack, short = false)
          if (context.debug) {
            appendLine(dumpGraph(short = false))
          }
        }

        with(context) { declarationToReport.reportError(message) }

        exitProcessing()
      }

    if (binding is Binding.Absent) {
      // Don't store this
      return binding
    }

    return binding
  }

  fun validate(node: DependencyGraphNode, onError: (String) -> Nothing): Set<TypeKey> {
    val deferredTypes = checkCycles(node, onError)
    checkMissingDependencies(onError)
    return deferredTypes
  }

  private fun checkCycles(node: DependencyGraphNode, onError: (String) -> Nothing): Set<TypeKey> {
    val visited = mutableSetOf<TypeKey>()
    val stack = BindingStack(node.sourceGraph)
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
        // TODO if a type is already deferred, skip the entries check?
        if (entriesInCycle.none { it.contextKey.isDeferrable }) {
          // Pull the root entry from the stack and add it back to the bottom of the stack to
          // highlight the cycle
          val fullCycle = entriesInCycle + entriesInCycle[0]

          val message = buildString {
            appendLine("[Lattice/DependencyCycle] Found a dependency cycle:")
            appendBindingStackEntries(
              stack.graph.kotlinFqName,
              fullCycle,
              ellipse = true,
              short = false,
            )
          }
          onError(message)
        } else {
          deferredTypes += key
        }
      }

      if (key in visited) return

      visited += key

      dependencies[key]?.value?.forEach { contextDep ->
        val dep = contextDep.typeKey
        val dependencyBinding = requireBinding(dep)
        val nextEntry = bindingStackEntryForDependency(binding, contextKey, dep)
        stack.withEntry(nextEntry) { dfs(dependencyBinding, contextDep) }
      }
    }

    for ((key, entry) in exposedTypes) {
      stack.withEntry(entry) {
        val binding = getOrCreateBinding(key, stack)
        dfs(binding)
      }
    }
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
    type: IrClass,
    bindingStack: BindingStack,
  ): Set<ContextualTypeKey> {
    val constructor = with(context) { type.findInjectableConstructor() }!!
    return getFunctionDependencies(constructor, bindingStack)
  }

  private fun getFunctionDependencies(
    function: IrFunction,
    bindingStack: BindingStack,
  ): Set<ContextualTypeKey> {
    return function.valueParameters
      .filterNot { it.isAnnotatedWithAny(context.symbols.assistedAnnotations) }
      .mapNotNull { param ->
        val paramKey = ContextualTypeKey.from(context, param)
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
  internal fun dumpGraph(short: Boolean): String {
    if (bindings.isEmpty()) return "Empty binding graph"

    return buildString {
      appendLine("Binding Graph:")
      // Sort by type key for consistent output
      bindings.entries
        .sortedBy { it.key.toString() }
        .forEach { (typeKey, binding) ->
          appendLine("─".repeat(50))
          appendLine("Type: ${typeKey.render(short)}")
          appendLine("├─ Binding: ${binding::class.simpleName}")
          appendLine("├─ Contextual Type: ${binding.contextualTypeKey.render(short)}")

          binding.scope?.let { scope -> appendLine("├─ Scope: $scope") }

          if (binding.dependencies.isNotEmpty()) {
            appendLine("├─ Dependencies:")
            binding.dependencies.forEach { (depKey, param) ->
              appendLine("│  ├─ ${depKey.render(short)}")
              appendLine("│  │  └─ Parameter: ${param.name} (${param.type})")
            }
          }

          if (binding.parameters.allParameters.isNotEmpty()) {
            appendLine("├─ Parameters:")
            binding.parameters.allParameters.forEach { param ->
              appendLine("│  └─ ${param.name}: ${param.contextualTypeKey.render(short)}")
            }
          }

          binding.reportableLocation?.let { location -> appendLine("└─ Location: $location") }
        }
    }
  }
}
