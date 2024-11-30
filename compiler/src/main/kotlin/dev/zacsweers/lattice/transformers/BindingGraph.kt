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
import dev.zacsweers.lattice.ir.rawType
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
        // TODO this validates too soon, need to maybe add shallow bindings first?
        //  or add it but defer validation until validate()
        is Binding.Provided -> getFunctionDependencies(binding.providerFunction, bindingStack)
        is Binding.ComponentDependency -> emptySet()
      }
    }
  }

  // For bindings we expect to already be cached
  fun requireBinding(key: TypeKey): Binding = bindings[key] ?: error("No binding found for $key")

  fun getOrCreateBinding(key: TypeKey, bindingStack: BindingStack): Binding {
    return bindings.getOrPut(key) {
      // If no explicit binding exists, check if type is injectable
      val irClass = key.type.rawType()
      val injectableConstructor = with(context) { irClass.findInjectableConstructor() }
      if (injectableConstructor != null) {
        val parameters = injectableConstructor.parameters(context)
        Binding.ConstructorInjected(
          type = irClass,
          typeKey = key,
          parameters = parameters,
          scope = with(context) { irClass.scopeAnnotation() },
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

  fun validate() {
    checkCycles()
    checkMissingDependencies()
  }

  private fun checkCycles() {
    val visited = mutableSetOf<TypeKey>()
    val stack = ArrayDeque<TypeKey>()

    fun dfs(key: TypeKey) {
      if (key in stack) {
        // TODO check if there's a lazy in the stack, if so we can break the cycle
        //  A -> B -> Lazy<A> is valid
        //  A -> B -> A is not
        // TODO IR error instead
        error("Dependency cycle detected: ${stack.joinToString(" -> ")} -> $key")
      }
      if (key in visited) return

      visited.add(key)
      stack.add(key)
      dependencies[key]?.value?.forEach { dep -> dfs(dep) }
      stack.remove(key)
    }

    bindings.keys.forEach { dfs(it) }
  }

  private fun checkMissingDependencies() {
    val allDeps = dependencies.values.map { it.value }.flatten().toSet()
    val missing = allDeps - bindings.keys
    check(missing.isEmpty()) { "Missing bindings for: $missing" }
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
