// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.graph.MutableBindingGraph
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.util.kotlinFqName

internal class IrBindingGraph(
  private val metroContext: IrMetroContext,
  newBindingStack: () -> IrBindingStack,
) {

  private val realGraph =
    MutableBindingGraph(
      newBindingStack = newBindingStack,
      newBindingStackEntry = { contextKey, binding ->
        bindingStackEntryForDependency(binding, contextKey, contextKey.typeKey)
      },
      computeBinding = { contextKey, stack ->
        metroContext.injectedClassBindingOrNull(contextKey, stack, this)
      },
      onError = { message, stack ->
        val location = stack.lastEntryOrGraph.locationOrNull()
        metroContext.reportError(message, location)
        exitProcessing()
      },
      findSimilarBindings = { key -> findSimilarBindings(key).mapValues { it.value.toString() } },
      stackLogger = metroContext.loggerFor(MetroLogger.Type.BindingGraphConstruction),
    )

  // Use ConcurrentHashMap to allow reentrant modification
  // TODO hoist accessors up and visit in seal?
  private val accessors = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  private val injectors = mutableMapOf<IrTypeKey, IrBindingStack.Entry>()

  // Thin immutable view over the internal bindings
  fun bindingsSnapshot(): Map<IrTypeKey, Binding> = realGraph.snapshot

  fun addAccessor(key: IrContextualTypeKey, entry: IrBindingStack.Entry) {
    accessors[key] = entry
  }

  fun addInjector(key: IrTypeKey, entry: IrBindingStack.Entry) {
    injectors[key] = entry
  }

  fun addBinding(key: IrTypeKey, binding: Binding, bindingStack: IrBindingStack) {
    if (binding is Binding.Absent) {
      // Don't store absent bindings
      return
    }
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
        stack.push(
          IrBindingStack.Entry.simpleTypeRef(IrContextualTypeKey.create(contextKey.typeKey))
        )
        val message = buildString {
          appendLine("No binding found for ${contextKey.typeKey}")
          appendBindingStack(stack)
          if (metroContext.debug) {
            appendLine(dumpGraph(stack.graph.kotlinFqName.asString(), short = false))
          }
        }
        metroContext.reportError(message, stack.lastEntryOrGraph.location())
        exitProcessing()
      }
  }

  fun getOrCreateMultibinding(
    pluginContext: IrPluginContext,
    typeKey: IrTypeKey,
    bindingStack: IrBindingStack,
  ): Binding.Multibinding {
    var binding = realGraph[typeKey]

    if (binding == null) {
      binding = Binding.Multibinding.create(metroContext, typeKey, null)
      realGraph.tryPut(binding, bindingStack)
      // If it's a map, expose a binding for Map<KeyType, Provider<ValueType>>
      if (binding.isMap) {
        val keyType = (typeKey.type as IrSimpleType).arguments[0].typeOrNull!!
        val valueType =
          typeKey.type.arguments[1]
            .typeOrNull!!
            .wrapInProvider(this@IrBindingGraph.metroContext.symbols.metroProvider)
        val providerTypeKey =
          typeKey.copy(type = pluginContext.irBuiltIns.mapClass.typeWith(keyType, valueType))
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

  fun getOrCreateBinding(contextKey: IrContextualTypeKey, bindingStack: IrBindingStack): Binding {
    check(!realGraph.sealed)
    val key = contextKey.typeKey
    val existingBinding = realGraph[key]
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

  operator fun contains(key: IrTypeKey): Boolean = key in realGraph

  fun IrTypeKey.dependsOn(key: IrTypeKey) = with(realGraph) { this@dependsOn.dependsOn(key) }

  fun validate(onError: (String) -> Nothing): Set<IrTypeKey> {
    val deferredTypes = realGraph.seal(accessors)
    checkEmptyMultibindings(onError)
    check(realGraph.snapshot.values.none { it is Binding.Absent }) {
      "Found absent bindings in the binding graph: ${dumpGraph("Absent bindings", short = true)}"
    }
    return deferredTypes
  }

  private fun checkEmptyMultibindings(onError: (String) -> Nothing) {
    val multibindings = realGraph.snapshot.values.filterIsInstance<Binding.Multibinding>()
    for (multibinding in multibindings) {
      if (!multibinding.allowEmpty && multibinding.sourceBindings.isEmpty()) {
        val message = buildString {
          appendLine(
            "[Metro/EmptyMultibinding] Multibinding '${multibinding.typeKey}' was unexpectedly empty."
          )
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
        onError(message)
      }
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

  private fun reportMissingBinding(typeKey: IrTypeKey, bindingStack: IrBindingStack): Nothing {
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
        similarBindings.values.map { "  - $it" }.sorted().forEach(::appendLine)
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
    realGraph.snapshot.forEach { (bindingKey, binding) ->
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

    // TODO filter out source bindings in multibindings? Should be covered though
    return similarBindings
  }

  // TODO iterate on this more!
  internal fun dumpGraph(name: String, short: Boolean): String {
    if (realGraph.snapshot.isEmpty()) return "Empty binding graph"

    return buildString {
      appendLine("Binding Graph: $name")
      // Sort by type key for consistent output
      realGraph.snapshot.entries
        .sortedBy { it.key.toString() }
        .forEach { (_, binding) ->
          appendLine("─".repeat(50))
          appendBinding(binding, short, isNested = false)
        }
    }
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
