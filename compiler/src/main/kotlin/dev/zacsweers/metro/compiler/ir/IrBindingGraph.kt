// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.graph.MutableBindingGraph
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.util.kotlinFqName

internal class IrBindingGraph(
  private val metroContext: IrMetroContext,
  private val node: DependencyGraphNode,
  newBindingStack: () -> IrBindingStack,
) {

  private val realGraph =
    MutableBindingGraph(
      newBindingStack = newBindingStack,
      newBindingStackEntry = { contextKey, callingBinding, roots ->
        if (callingBinding == null) {
          roots.getValue(contextKey)
        } else {
          bindingStackEntryForDependency(callingBinding, contextKey, contextKey.typeKey)
        }
      },
      absentBinding = { key -> Binding.Absent(key) },
      computeBinding = { contextKey -> metroContext.injectedClassBindingOrNull(contextKey) },
      onError = ::onError,
      findSimilarBindings = { key -> findSimilarBindings(key).mapValues { it.value.toString() } },
    )

  // TODO hoist accessors up and visit in seal?
  private val accessors = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  private val injectors = mutableMapOf<IrTypeKey, IrBindingStack.Entry>()

  // Thin immutable view over the internal bindings
  fun bindingsSnapshot(): Map<IrTypeKey, Binding> = realGraph.bindings

  fun addAccessor(key: IrContextualTypeKey, entry: IrBindingStack.Entry) {
    accessors[key] = entry
  }

  fun addInjector(key: IrTypeKey, entry: IrBindingStack.Entry) {
    injectors[key] = entry
  }

  fun addBinding(key: IrTypeKey, binding: Binding, bindingStack: IrBindingStack) {
    realGraph.tryPut(binding, bindingStack, key)
  }

  fun findBinding(key: IrTypeKey): Binding? = realGraph[key]

  // For bindings we expect to already be cached
  fun requireBinding(key: IrTypeKey, stack: IrBindingStack): Binding {
    return requireBinding(IrContextualTypeKey.create(key), stack)
  }

  fun requireBinding(contextKey: IrContextualTypeKey, stack: IrBindingStack): Binding {
    return realGraph[contextKey.typeKey]
      ?: run {
        if (contextKey.hasDefault) return Binding.Absent(contextKey.typeKey)
        realGraph.reportMissingBinding(contextKey.typeKey, stack) {
          if (metroContext.debug) {
            appendLine(dumpGraph(stack.graph.kotlinFqName.asString(), short = false))
          }
        }
      }
  }

  fun getOrCreateMultibinding(
    pluginContext: IrPluginContext,
    annotations: MetroAnnotations<IrAnnotation>,
    contextKey: IrContextualTypeKey,
    declaration: IrSimpleFunction,
    originalQualifier: IrAnnotation?,
    bindingStack: IrBindingStack,
  ): Binding.Multibinding {
    val multibindingType =
      when {
        annotations.isIntoSet -> {
          metroContext.pluginContext.irBuiltIns.setClass.typeWith(contextKey.typeKey.type)
        }

        annotations.isElementsIntoSet -> contextKey.typeKey.type
        annotations.isIntoMap -> {
          val mapKey =
            annotations.mapKeys.firstOrNull()
              ?: run {
                // Hard error because the FIR checker should catch these, so this implies broken
                // FIR code gen
                error("Missing @MapKey for @IntoMap function: ${declaration.locationOrNull()}")
              }
          val keyType = metroContext.mapKeyType(mapKey)
          metroContext.pluginContext.irBuiltIns.mapClass.typeWith(
            // MapKey is the key type
            keyType,
            // Return type is the value type
            contextKey.typeKey.type.removeAnnotations(),
          )
        }

        else -> {
          error("Unrecognized provider: ${declaration.locationOrNull()}")
        }
      }

    val multibindingTypeKey =
      contextKey.typeKey.copy(type = multibindingType, qualifier = originalQualifier)

    var binding = realGraph[multibindingTypeKey]

    if (binding == null) {
      binding = Binding.Multibinding.fromContributor(metroContext, multibindingTypeKey)
      realGraph.tryPut(binding, bindingStack)
      // If it's a map, expose a binding for Map<KeyType, Provider<ValueType>>
      if (binding.isMap) {
        val keyType = (binding.typeKey.type as IrSimpleType).arguments[0].typeOrNull!!
        val valueType =
          binding.typeKey.type.arguments[1]
            .typeOrNull!!
            .wrapInProvider(this@IrBindingGraph.metroContext.symbols.metroProvider)
        val providerTypeKey =
          binding.typeKey.copy(
            type = pluginContext.irBuiltIns.mapClass.typeWith(keyType, valueType)
          )
        realGraph.tryPut(binding, bindingStack, providerTypeKey)
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

  operator fun contains(key: IrTypeKey): Boolean = key in realGraph

  data class BindingGraphResult(
    val sortedKeys: List<IrTypeKey>,
    val deferredTypes: List<IrTypeKey>,
  )

  data class GraphError(val declaration: IrDeclaration?, val message: String)

  fun validate(parentTracer: Tracer, onError: (List<GraphError>) -> Nothing): BindingGraphResult {
    val (sortedKeys, deferredTypes) =
      parentTracer.traceNested("seal graph") { tracer ->
        realGraph.seal(accessors, tracer, validateBinding = ::checkScope)
      }

    metroContext.writeDiagnostic("validatedKeys-${parentTracer.tag}.txt") {
      buildString { sortedKeys.joinTo(this, separator = "\n") { it.render(short = false) } }
    }
    metroContext.writeDiagnostic("deferredTypes-${parentTracer.tag}.txt") {
      buildString { deferredTypes.joinTo(this, separator = "\n") { it.render(short = false) } }
    }

    parentTracer.traceNested("check empty multibindings") { checkEmptyMultibindings(onError) }
    parentTracer.traceNested("check for absent bindings") {
      check(realGraph.bindings.values.none { it is Binding.Absent }) {
        "Found absent bindings in the binding graph: ${dumpGraph("Absent bindings", short = true)}"
      }
    }
    return BindingGraphResult(sortedKeys, deferredTypes)
  }

  private fun checkEmptyMultibindings(onError: (List<GraphError>) -> Nothing) {
    val multibindings = realGraph.bindings.values.filterIsInstance<Binding.Multibinding>()
    val errors = mutableListOf<GraphError>()
    for (multibinding in multibindings) {
      if (!multibinding.allowEmpty && multibinding.sourceBindings.isEmpty()) {
        val message = buildString {
          append("[Metro/EmptyMultibinding] Multibinding '")
          append(multibinding.typeKey)
          appendLine("' was unexpectedly empty.")

          appendLine()
          appendLine(
            "If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`."
          )

          val similarBindings = findSimilarMultibindings(multibinding, multibindings).toList()
          if (similarBindings.isNotEmpty()) {
            appendLine()
            appendLine("Similar multibindings:")
            val reported = mutableSetOf<IrTypeKey>()
            for (key in similarBindings) {
              if (key in reported) continue
              appendLine("- ${key.render(short = true)}")
              reported += key
            }
          }
        }
        val declarationToReport =
          if (multibinding.declaration?.isFakeOverride == true) {
            multibinding.declaration!!
              .overriddenSymbolsSequence()
              .firstOrNull { !it.owner.isFakeOverride }
              ?.owner
          } else {
            multibinding.declaration
          }
        errors += GraphError(declarationToReport, message)
      }
    }
    if (errors.isNotEmpty()) {
      onError(errors)
    }
  }

  private fun findSimilarMultibindings(
    multibinding: Binding.Multibinding,
    multibindings: List<Binding.Multibinding>,
  ): Sequence<IrTypeKey> = sequence {
    if (multibinding.isMap) {
      val keyType = multibinding.typeKey.requireMapKeyType()
      val valueType = multibinding.typeKey.requireMapValueType()
      val similarKeys =
        multibindings
          .filter { it.isMap && it != multibinding && it.typeKey.requireMapKeyType() == keyType }
          .map { it.typeKey }

      yieldAll(similarKeys)

      val similarValues =
        multibindings
          .filter {
            if (!it.isMap) return@filter false
            if (it == multibinding) return@filter false
            val otherValueType = it.typeKey.requireMapValueType()
            if (valueType == otherValueType) return@filter true
            if (valueType.isSubtypeOf(otherValueType, metroContext.irTypeSystemContext))
              return@filter true
            if (otherValueType.isSubtypeOf(valueType, metroContext.irTypeSystemContext))
              return@filter true
            false
          }
          .map { it.typeKey }

      yieldAll(similarValues)
    } else {
      // Set binding
      val elementType = multibinding.typeKey.requireSetElementType()

      val similar =
        multibindings
          .filter {
            if (!it.isSet) return@filter false
            if (it == multibinding) return@filter false
            val otherElementType = it.typeKey.requireSetElementType()
            if (elementType == otherElementType) return@filter true
            if (elementType.isSubtypeOf(otherElementType, metroContext.irTypeSystemContext))
              return@filter true
            if (otherElementType.isSubtypeOf(elementType, metroContext.irTypeSystemContext))
              return@filter true
            false
          }
          .map { it.typeKey }

      yieldAll(similar)
    }
  }

  private fun findSimilarBindings(key: IrTypeKey): Map<IrTypeKey, SimilarBinding> {
    // Use a map to avoid reporting duplicates
    val similarBindings = mutableMapOf<IrTypeKey, SimilarBinding>()

    // Same type with different qualifier
    if (key.qualifier != null) {
      findBinding(key.copy(qualifier = null))?.let {
        similarBindings.putIfAbsent(it.typeKey, SimilarBinding(it, "Different qualifier"))
      }
    }

    // Check for nullable/non-nullable equivalent
    val isNullable = key.type.isMarkedNullable()
    val equivalentType =
      if (isNullable) {
        key.type.makeNotNull()
      } else {
        key.type.makeNullable()
      }
    val equivalentKey = key.copy(type = equivalentType)
    findBinding(equivalentKey)?.let {
      val nullabilityDescription =
        if (isNullable) "Non-nullable equivalent" else "Nullable equivalent"
      similarBindings.putIfAbsent(it.typeKey, SimilarBinding(it, nullabilityDescription))
    }

    // Little more involved, iterate the bindings for ones with the same type
    realGraph.bindings.forEach { (bindingKey, binding) ->
      when {
        key.qualifier == null && bindingKey.type == key.type -> {
          similarBindings.putIfAbsent(bindingKey, SimilarBinding(binding, "Different qualifier"))
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
            similarBindings.putIfAbsent(bindingKey, SimilarBinding(binding, "Multibinding"))
          }
        }
        bindingKey.type == key.type -> {
          // Already covered above but here to avoid falling through to the subtype checks
          // below as they would always return true for this
        }
        bindingKey.type.isSubtypeOf(key.type, metroContext.irTypeSystemContext) -> {
          similarBindings.putIfAbsent(bindingKey, SimilarBinding(binding, "Subtype"))
        }
        key.type.type.isSubtypeOf(bindingKey.type, metroContext.irTypeSystemContext) -> {
          similarBindings.putIfAbsent(bindingKey, SimilarBinding(binding, "Supertype"))
        }
      }
    }

    return similarBindings.filterNot {
      (it.value.binding as? Binding.BindingWithAnnotations)?.annotations?.isIntoMultibinding == true
    }
  }

  // TODO iterate on this more!
  internal fun dumpGraph(name: String, short: Boolean): String {
    if (realGraph.bindings.isEmpty()) return "Empty binding graph"

    return buildString {
      append("Binding Graph: ")
      appendLine(name)
      // Sort by type key for consistent output
      realGraph.bindings.entries
        .sortedBy { it.key.toString() }
        .forEach { (_, binding) ->
          appendLine("─".repeat(50))
          appendBinding(binding, short, isNested = false)
        }
    }
  }

  private fun onError(message: String, stack: IrBindingStack): Nothing {
    val location = stack.lastEntryOrGraph.locationOrNull()
    metroContext.reportError(message, location)
    exitProcessing()
  }

  // Check scoping compatibility
  // TODO FIR error?
  private fun checkScope(
    binding: Binding,
    stack: IrBindingStack,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    val bindingScope = binding.scope
    if (bindingScope != null) {
      if (node.scopes.isEmpty() || bindingScope !in node.scopes) {
        val isUnscoped = node.scopes.isEmpty()
        // Error if there are mismatched scopes
        val declarationToReport = node.sourceGraph
        val backTrace = buildRouteToRoot(binding.typeKey, roots, adjacency)
        for (entry in backTrace) {
          stack.push(entry)
        }
        stack.push(
          IrBindingStack.Entry.simpleTypeRef(
            binding.contextualTypeKey,
            usage = "(scoped to '$bindingScope')",
          )
        )
        val message = buildString {
          append("[Metro/IncompatiblyScopedBindings] ")
          append(declarationToReport.kotlinFqName)
          if (isUnscoped) {
            // Unscoped graph but scoped binding
            append(" (unscoped) may not reference scoped bindings:")
          } else {
            // Scope mismatch
            append(
              " (scopes ${node.scopes.joinToString { "'$it'" }}) may not reference bindings from different scopes:"
            )
          }
          appendLine()
          appendBindingStack(stack, short = false)
          if (!isUnscoped && binding is Binding.ConstructorInjected) {
            val matchingParent =
              node.allExtendedNodes.values.firstOrNull { bindingScope in it.scopes }
            if (matchingParent != null) {
              appendLine()
              appendLine()
              val shortTypeKey = binding.typeKey.render(short = true)
              appendLine(
                """
                  (Hint)
                  It appears that extended parent graph '${matchingParent.sourceGraph.kotlinFqName}' does declare the '$bindingScope' scope but doesn't use '$shortTypeKey' directly.
                  To work around this, consider declaring an accessor for '$shortTypeKey' in that graph (i.e. `val ${shortTypeKey.decapitalizeUS()}: $shortTypeKey`).
                  See https://github.com/ZacSweers/metro/issues/377 for more details.
                """
                  .trimIndent()
              )
            }
          }
        }
        with(metroContext) { declarationToReport.reportError(message) }
      }
    }
  }

  /**
   * Builds a route from this binding back to one of the root bindings. Useful for error messaging
   * to show a trace back to an entry point.
   */
  private fun buildRouteToRoot(
    key: IrTypeKey,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ): List<IrBindingStack.Entry> {
    // Build who depends on what
    val dependents = mutableMapOf<IrTypeKey, MutableSet<IrTypeKey>>()
    for ((key, deps) in adjacency) {
      for (dep in deps) {
        dependents.getOrPut(dep) { mutableSetOf() }.add(key)
      }
    }

    // Walk backwards from this binding to find a root
    val visited = mutableSetOf<IrTypeKey>()

    fun walkToRoot(current: IrTypeKey, path: List<IrTypeKey>): List<IrTypeKey>? {
      if (current in visited) return null // Cycle

      // Is this a root?
      if (roots.any { it.key.typeKey == current }) {
        return path + current
      }

      visited.add(current)

      // Try walking through each dependent
      for (dependent in dependents[current].orEmpty()) {
        walkToRoot(dependent, path + current)?.let {
          return it
        }
      }

      visited.remove(current)
      return null
    }

    val path = walkToRoot(key, emptyList()) ?: return emptyList()

    // Convert to stack entries - just create a simple stack and build it up
    val result = mutableListOf<IrBindingStack.Entry>()

    for (i in path.indices.reversed()) {
      val typeKey = path[i]

      if (i == path.lastIndex) {
        // This is the root
        val rootEntry = roots.entries.first { it.key.typeKey == typeKey }.value
        result.add(0, rootEntry)
      } else {
        // Create an entry for this step
        val callingBinding = realGraph.bindings.getValue(path[i + 1])
        val contextKey = callingBinding.dependencies.first { it.typeKey == typeKey }
        val entry = bindingStackEntryForDependency(callingBinding, contextKey, contextKey.typeKey)
        result.add(0, entry)
      }
    }

    // Reverse the route as these will push onto the top of the stack
    return result.asReversed()
  }

  private fun Appendable.appendBinding(binding: Binding, short: Boolean, isNested: Boolean) {
    appendLine("Type: ${binding.typeKey.render(short)}")
    appendLine("├─ Binding: ${binding::class.simpleName}")
    appendLine("├─ Contextual Type: ${binding.contextualTypeKey.render(short)}")

    binding.scope?.let { scope -> appendLine("├─ Scope: $scope") }

    if (binding is Binding.Alias) {
      appendLine("├─ Aliased type: ${binding.aliasedType.render(short)}")
    }

    if (binding.parametersByKey.isNotEmpty()) {
      appendLine("├─ Dependencies:")
      binding.parametersByKey.forEach { (depKey, param) ->
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
      binding.sourceBindings.forEach { sourceBindingKey ->
        val sourceBinding = requireBinding(sourceBindingKey, IrBindingStack.empty())
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
