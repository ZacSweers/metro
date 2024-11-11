package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.exitProcessing
import dev.zacsweers.lattice.ir.rawType
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.util.kotlinFqName

internal class BindingGraph(private val context: LatticeTransformerContext) {
  private val bindings = mutableMapOf<TypeKey, Binding>()
  private val dependencies = mutableMapOf<TypeKey, Set<TypeKey>>()

  fun addBinding(key: TypeKey, binding: Binding, bindingStack: BindingStack) {
    require(!bindings.containsKey(key)) { "Duplicate binding for $key" }
    bindings[key] = binding

    val deps =
      when (binding) {
        is Binding.ConstructorInjected -> {
          // Recursively follow deps from its constructor params
          getConstructorDependencies(binding.type, bindingStack)
        }
        is Binding.Provided -> getFunctionDependencies(binding.providerFunction, bindingStack)
        is Binding.ComponentDependency -> emptySet()
      }
    dependencies[key] = deps
  }

  fun getOrCreateBinding(key: TypeKey, bindingStack: BindingStack): Binding {
    return bindings.getOrPut(key) {
      // If no explicit binding exists, check if type is injectable
      val irClass = key.type.rawType()
      val injectableConstructor = with(context) { irClass.findInjectableConstructor() }
      if (injectableConstructor != null) {
        val dependencies =
          injectableConstructor.valueParameters.mapToConstructorParameters(context).associateBy {
            it.typeKey
          }
        bindingStack.pop()
        Binding.ConstructorInjected(
          type = irClass,
          dependencies = dependencies,
          scope = with(context) { irClass.scopeAnnotation() },
        )
      } else {
        val entries = bindingStack.entries
        val declarationToReport = entries.firstOrNull()?.declaration ?: bindingStack.component
        val message = buildString {
          append(
            "Cannot find an @Inject constructor or @Provides-annotated function/property for: "
          )
          appendLine(key)
          appendLine()
          val componentName = bindingStack.component.kotlinFqName
          for (entry in entries) {
            entry.render(componentName).prependIndent("    ").lineSequence().forEach {
              appendLine(it)
            }
          }
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
      check(key !in stack) { "Dependency cycle detected: ${stack.joinToString(" -> ")} -> $key" }
      if (key in visited) return

      visited.add(key)
      stack.add(key)
      dependencies[key]?.forEach { dep -> dfs(dep) }
      stack.remove(key)
    }

    bindings.keys.forEach { dfs(it) }
  }

  private fun checkMissingDependencies() {
    val allDeps = dependencies.values.flatten().toSet()
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
        val paramKey = TypeKey(param.type, with(context) { param.qualifierAnnotation() })
        bindingStack.push(BindingStackEntry.injectedAt(paramKey, function, param))
        // This recursive call will create bindings for injectable types as needed
        getOrCreateBinding(paramKey, bindingStack)
        paramKey
      }
      .toSet()
  }
}