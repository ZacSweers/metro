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
package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.exitProcessing
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.ir.rawType
import dev.zacsweers.lattice.ir.singleAbstractFunction
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction

internal class BindingGraph(private val context: LatticeTransformerContext) {
  private val bindings = mutableMapOf<TypeKey, Binding>()
  private val dependencies = mutableMapOf<TypeKey, Lazy<Set<TypeKey>>>()

  fun addBinding(key: TypeKey, binding: Binding, bindingStack: BindingStack) {
    require(!bindings.containsKey(key)) { "Duplicate binding for $key" }
    bindings[key] = binding

    // Lazily evaluate dependencies so that we get a shallow set of keys
    // upfront but can defer resolution of bindings from dependencies until
    // the full graph is actualized.
    // Otherwise, this scenario wouldn't work in this order:
    //
    // val charSequenceValue: CharSequence
    // @Provides fun bind(stringValue: String): CharSequence = this
    // @Provides fun provideString(): String = "Hi"
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
        is Binding.Assisted -> getFunctionDependencies(binding.function, bindingStack)
        is Binding.Multibinding -> {
          // This is a manual @Multibinds or triggered by the above
          // This type's dependencies are just its providers' dependencies
          binding.providers.flatMapTo(mutableSetOf()) {
            getFunctionDependencies(it.providerFunction, bindingStack)
          }
        }
        is Binding.BoundInstance -> emptySet()
        is Binding.ComponentDependency -> emptySet()
      }
    }
  }

  // For bindings we expect to already be cached
  fun requireBinding(key: TypeKey): Binding = bindings[key] ?: error("No binding found for $key")

  fun getOrCreateMultibinding(
    pluginContext: IrPluginContext,
    typeKey: TypeKey,
  ): Binding.Multibinding {
    return bindings.getOrPut(typeKey) {
      Binding.Multibinding.create(pluginContext, typeKey).also {
        addBinding(typeKey, it, BindingStack.empty())
      }
    } as Binding.Multibinding
  }

  fun getOrCreateBinding(key: TypeKey, bindingStack: BindingStack): Binding {
    return bindings.getOrPut(key) {
      // If no explicit binding exists, check if type is injectable
      val irClass = key.type.rawType()
      val injectableConstructor = with(context) { irClass.findInjectableConstructor() }
      if (injectableConstructor != null) {
        val parameters = injectableConstructor.parameters(context)
        Binding.ConstructorInjected(
          type = irClass,
          injectedConstructor = injectableConstructor,
          typeKey = key,
          parameters = parameters,
          scope = with(context) { irClass.scopeAnnotation() },
        )
      } else if (with(context) { irClass.isAnnotatedWithAny(symbols.assistedFactoryAnnotations) }) {
        val function = irClass.singleAbstractFunction(context)
        val targetTypeMetadata = TypeMetadata.from(context, function)
        val bindingStackEntry = BindingStackEntry.injectedAt(key, function)
        val targetBinding =
          bindingStack.withEntry(bindingStackEntry) {
            getOrCreateBinding(targetTypeMetadata.typeKey, bindingStack)
          } as Binding.ConstructorInjected
        Binding.Assisted(
          type = irClass,
          function = function,
          typeKey = key,
          parameters = function.parameters(context),
          target = targetBinding,
        )
      } else {
        val declarationToReport = bindingStack.lastEntryOrComponent
        val message = buildString {
          append(
            "[Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: "
          )
          appendLine(key)
          appendLine()
          appendBindingStack(bindingStack)
        }

        with(context) { declarationToReport.reportError(message) }

        exitProcessing()
      }
    }
  }

  fun validate(component: ComponentNode, onError: (String) -> Nothing) {
    checkCycles(component, onError)
    checkMissingDependencies(onError)
  }

  private fun checkCycles(component: ComponentNode, onError: (String) -> Nothing) {
    val visited = mutableSetOf<TypeKey>()
    val stack = BindingStack(component.sourceComponent)

    fun dfs(binding: Binding) {
      val key = binding.typeKey
      val existingEntry = stack.entryFor(key)
      if (existingEntry != null) {
        // TODO check if there's a lazy in the stack, if so we can break the cycle
        //  A -> B -> Lazy<A> is valid
        //  A -> B -> A is not

        // Pull the root entry from the stack and push it back to the top to highlight the cycle
        stack.push(existingEntry)

        val message = buildString {
          appendLine("[Lattice/DependencyCycle] Found a dependency cycle:")
          appendBindingStack(stack, ellipse = true)
        }
        onError(message)
      }

      if (key in visited) return

      visited += key

      dependencies[key]?.value?.forEach { dep ->
        val dependencyBinding = requireBinding(dep)
        val entry =
          when (binding) {
            is Binding.ConstructorInjected -> {
              BindingStackEntry.injectedAt(
                key,
                binding.injectedConstructor,
                binding.parameterFor(dep),
                displayTypeKey = dep,
              )
            }
            is Binding.Provided -> {
              BindingStackEntry.injectedAt(
                key,
                binding.providerFunction,
                binding.parameterFor(dep),
                displayTypeKey = dep,
              )
            }
            is Binding.Assisted -> {
              BindingStackEntry.injectedAt(key, binding.function, displayTypeKey = dep)
            }
            is Binding.Multibinding -> {
              TODO()
            }
            is Binding.BoundInstance -> TODO()
            is Binding.ComponentDependency -> TODO()
          }
        stack.withEntry(entry) { dfs(dependencyBinding) }
      }
    }

    for ((key, binding) in bindings) {
      // TODO need type metadata here to allow cycle breaking
      dfs(binding)
    }
  }

  private fun checkMissingDependencies(onError: (String) -> Nothing) {
    val allDeps = dependencies.values.map { it.value }.flatten().toSet()
    val missing = allDeps - bindings.keys
    if (missing.isNotEmpty()) {
      onError("Missing bindings for: $missing")
    }
  }

  private fun getConstructorDependencies(type: IrClass, bindingStack: BindingStack): Set<TypeKey> {
    val constructor = with(context) { type.findInjectableConstructor() }!!
    return getFunctionDependencies(constructor, bindingStack)
  }

  private fun getFunctionDependencies(
    function: IrFunction,
    bindingStack: BindingStack,
  ): Set<TypeKey> {
    return function.valueParameters
      .map { param ->
        val paramKey = TypeMetadata.from(context, param).typeKey
        bindingStack.withEntry(BindingStackEntry.injectedAt(paramKey, function, param)) {
          // This recursive call will create bindings for injectable types as needed
          getOrCreateBinding(paramKey, bindingStack)
        }
        paramKey
      }
      .toSet()
  }
}
