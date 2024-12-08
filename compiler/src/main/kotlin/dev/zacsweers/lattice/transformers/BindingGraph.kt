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
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction

internal class BindingGraph(private val context: LatticeTransformerContext) {
  private val bindings = mutableMapOf<TypeKey, BindingEntry>()
  private val dependencies = mutableMapOf<TypeKey, Lazy<Set<TypeKey>>>()

  data class BindingEntry(val binding: Binding, val metadata: TypeMetadata)

  fun addBinding(type: TypeMetadata, binding: Binding, bindingStack: BindingStack) {
    val key = type.typeKey
    require(!bindings.containsKey(key)) { "Duplicate binding for $key" }
    bindings[key] = BindingEntry(binding, type)

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
        is Binding.Provided -> getFunctionDependencies(binding.providerFunction, bindingStack)
        is Binding.Assisted -> getFunctionDependencies(binding.function, bindingStack)
        is Binding.BoundInstance -> emptySet()
        is Binding.ComponentDependency -> emptySet()
      }
    }
  }

  // For bindings we expect to already be cached
  fun requireBindingEntry(key: TypeKey): BindingEntry =
    bindings[key] ?: error("No binding found for $key")

  fun getOrCreateBindingEntry(type: TypeMetadata, bindingStack: BindingStack): Binding {
    val key = type.typeKey
    return bindings
      .getOrPut(key) {
        // If no explicit binding exists, check if type is injectable
        val irClass = key.type.rawType()
        val injectableConstructor = with(context) { irClass.findInjectableConstructor() }
        val binding =
          if (injectableConstructor != null) {
            val parameters = injectableConstructor.parameters(context)
            Binding.ConstructorInjected(
              type = irClass,
              injectedConstructor = injectableConstructor,
              typeKey = key,
              parameters = parameters,
              scope = with(context) { irClass.scopeAnnotation() },
            )
          } else if (
            with(context) { irClass.isAnnotatedWithAny(symbols.assistedFactoryAnnotations) }
          ) {
            val function = irClass.singleAbstractFunction(context)
            val targetTypeMetadata = TypeMetadata.from(context, function)
            val bindingStackEntry = BindingStackEntry.injectedAt(type, function)
            val targetBinding =
              bindingStack.withEntry(bindingStackEntry) {
                getOrCreateBindingEntry(targetTypeMetadata, bindingStack)
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
        BindingEntry(binding, type)
      }
      .binding
  }

  fun validate(component: ComponentNode, onError: (String) -> Nothing) {
    checkCycles(component, onError)
    checkMissingDependencies(onError)
  }

  private fun checkCycles(component: ComponentNode, onError: (String) -> Nothing) {
    val visited = mutableSetOf<TypeKey>()
    val stack = BindingStack(component.sourceComponent)

    fun dfs(entry: BindingEntry) {
      val binding = entry.binding
      val key = binding.typeKey
      val existingEntry = stack.entryFor(key)
      if (existingEntry != null) {
        // TODO check if there's a lazy in the stack, if so we can break the cycle
        //  A -> B -> Lazy<A> is valid
        //  A -> B -> A is not
        // TODO for some reason entries are not carrying correct TypeMetadata from dependencies
        if (stack.entries.none { it.metadata.isDeferrableType }) {
          // Pull the root entry from the stack and push it back to the top to highlight the cycle
          stack.push(existingEntry)

          val message = buildString {
            appendLine("[Lattice/DependencyCycle] Found a dependency cycle:")
            appendBindingStack(stack, ellipse = true)
          }
          onError(message)
        }
      }

      if (key in visited) return

      visited += key

      dependencies[key]?.value?.forEach { dep ->
        val dependencyBinding = requireBindingEntry(dep)
        val entry =
          when (binding) {
            is Binding.ConstructorInjected -> {
              BindingStackEntry.injectedAt(
                entry.metadata,
                binding.injectedConstructor,
                binding.parameterFor(dep),
                displayTypeKey = dep,
              )
            }
            is Binding.Provided -> {
              BindingStackEntry.injectedAt(
                entry.metadata,
                binding.providerFunction,
                binding.parameterFor(dep),
                displayTypeKey = dep,
              )
            }
            is Binding.Assisted -> {
              BindingStackEntry.injectedAt(entry.metadata, binding.function, displayTypeKey = dep)
            }
            is Binding.BoundInstance,
            is Binding.ComponentDependency -> error("Not possible")
          }
        stack.withEntry(entry) { dfs(dependencyBinding) }
      }
    }

    for ((_, binding) in bindings) {
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
        val paramType = TypeMetadata.from(context, param)
        bindingStack.withEntry(BindingStackEntry.injectedAt(paramType, function, param)) {
          // This recursive call will create bindings for injectable types as needed
          getOrCreateBindingEntry(paramType, bindingStack)
        }
        paramType.typeKey
      }
      .toSet()
  }
}
