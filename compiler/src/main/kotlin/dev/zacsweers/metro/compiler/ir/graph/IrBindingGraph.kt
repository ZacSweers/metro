// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.filterToSet
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.getValue
import dev.zacsweers.metro.compiler.graph.GraphAdjacency
import dev.zacsweers.metro.compiler.graph.MissingBindingHints
import dev.zacsweers.metro.compiler.graph.MutableBindingGraph
import dev.zacsweers.metro.compiler.graph.partitionBySCCs
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.bindingTypeOrNull
import dev.zacsweers.metro.compiler.ir.hasErrorTypes
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.locationOrNull
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.ir.renderSourceLocation
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireMapKeyType
import dev.zacsweers.metro.compiler.ir.requireMapValueType
import dev.zacsweers.metro.compiler.ir.requireSetElementType
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.safePathString
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses

private const val MAX_SUSPICIOUS_UNUSED_MULTIBINDINGS_TO_REPORT = 3

internal class IrBindingGraph(
  metroContext: IrMetroContext,
  private val node: GraphNode.Local,
  private val newBindingStack: () -> IrBindingStack,
  // TODO improve this cleanup
  bindingLookup: BindingLookup,
  private val contributionData: IrContributionData,
) : IrMetroContext by metroContext {
  private var hasErrors = false

  private var _bindingLookup: BindingLookup? = bindingLookup
    set(value) {
      if (value == null) {
        field?.clear()
      }
      field = value
    }

  private val bindingLookup
    get() =
      _bindingLookup ?: reportCompilerBug("Tried to access bindingLookup after it's been cleared!")

  private val realGraph =
    MutableBindingGraph<_, _, _, IrBinding, _, _>(
      newBindingStack = newBindingStack,
      newBindingStackEntry = { contextKey, callingBinding, roots ->
        if (callingBinding == null) {
          roots.getValue(contextKey)
        } else {
          bindingStackEntryForDependency(callingBinding, contextKey, contextKey.typeKey)
        }
      },
      computeBindings = { contextKey, currentBindings, stack ->
        this.bindingLookup.lookup(contextKey, currentBindings, stack) { key, bindings ->
          reportDuplicateBindings(key, bindings, stack)
        }
      },
      onError = ::onError,
      onHardError = { message, stack ->
        onError(message, stack)
        exitProcessing()
      },
      missingBindingHints = { key ->
        MissingBindingHints(
          missingBindingHints(key),
          findSimilarBindings(key).mapValues { it.value.render(short = true) },
        )
      },
    )

  // TODO hoist accessors up and visit in seal?
  private val accessors = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  private val injectors = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  private val extraKeeps = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  /**
   * Context keys that child graphs need from this parent. Used to ensure these bindings are
   * reachable during seal() and to inform BindingPropertyCollector about child usage.
   */
  private val reservedContextKeys = mutableSetOf<IrContextualTypeKey>()

  // Thin immutable view over the internal bindings
  fun bindingsSnapshot(): ScatterMap<IrTypeKey, IrBinding> = realGraph.bindings

  fun keeps(): Set<IrContextualTypeKey> = extraKeeps.keys

  fun addAccessor(key: IrContextualTypeKey, entry: IrBindingStack.Entry) {
    accessors[key] = entry
  }

  fun addInjector(key: IrContextualTypeKey, entry: IrBindingStack.Entry) {
    injectors[key] = entry
  }

  fun addBinding(key: IrTypeKey, binding: IrBinding, bindingStack: IrBindingStack) {
    realGraph.tryPut(binding, bindingStack, key)
  }

  fun keep(key: IrContextualTypeKey, entry: IrBindingStack.Entry) {
    extraKeeps[key] = entry
  }

  /**
   * Tracks a context key as requested by a child graph. This ensures the binding is kept during
   * seal() and informs BindingPropertyCollector about child usage.
   */
  fun reserveContextKey(contextKey: IrContextualTypeKey) {
    reservedContextKeys.add(contextKey)
  }

  /** Returns all context keys reserved by child graphs. */
  fun reservedContextKeys(): Set<IrContextualTypeKey> = reservedContextKeys

  /** Checks if a specific context key is reserved by child graphs. */
  fun isContextKeyReserved(contextKey: IrContextualTypeKey): Boolean =
    contextKey in reservedContextKeys

  /**
   * Checks if any variant (provider or instance) of this type key is reserved by child graphs.
   * Provider variant is checked first since scoped bindings need Provider fields.
   */
  fun hasReservedKey(key: IrTypeKey): Boolean {
    // Check provider key first
    val providerType = metroContext.metroSymbols.metroProvider.typeWith(key.type)
    val providerKey =
      IrContextualTypeKey.create(key, isWrappedInProvider = true, rawType = providerType)
    if (providerKey in reservedContextKeys) return true

    // Check instance key
    // TODO cache these in metrocontext. Just IrTypekey -> IrContextualTypeKey
    val instanceKey = IrContextualTypeKey.create(key)
    return instanceKey in reservedContextKeys
  }

  fun findBinding(key: IrTypeKey, allowLookup: Boolean = false): IrBinding? =
    realGraph[key]
      ?: run {
        if (allowLookup) {
          bindingLookup
            .lookup(IrContextualTypeKey(key), realGraph.bindings, IrBindingStack.empty()) { _, _ ->
              // handled separately
            }
            .firstOrNull()
        } else {
          null
        }
      }

  // For bindings we expect to already be cached
  fun requireBinding(key: IrTypeKey): IrBinding {
    return requireBinding(IrContextualTypeKey.create(key))
  }

  fun requireBinding(contextKey: IrContextualTypeKey): IrBinding {
    return realGraph[contextKey.typeKey]
      ?: if (contextKey.hasDefault) {
        IrBinding.Absent(contextKey.typeKey)
      } else {
        reportCompilerBug("No expected binding found for key $contextKey")
      }
  }

  operator fun contains(key: IrTypeKey): Boolean = key in realGraph

  data class BindingGraphResult(
    val sortedKeys: List<IrTypeKey>,
    val deferredTypes: Set<IrTypeKey>,
    val reachableKeys: Set<IrTypeKey>,
    val shardGroups: List<List<IrTypeKey>>?,
    // Map of unused keys to graph inputs, if available
    val unusedKeys: Map<IrTypeKey, IrBinding.BoundInstance?>,
    val hasErrors: Boolean,
  ) {
    companion object {
      val ERROR =
        BindingGraphResult(
          sortedKeys = emptyList(),
          deferredTypes = emptySet(),
          reachableKeys = emptySet(),
          shardGroups = emptyList(),
          unusedKeys = emptyMap(),
          hasErrors = true,
        )
    }
  }

  data class GraphError(val declaration: IrDeclaration?, val message: String)

  context(traceScope: TraceScope)
  fun seal(onError: (List<GraphError>) -> Unit): BindingGraphResult {
    val topologyResult =
      trace("seal graph") {
        val roots = buildMap {
          putAll(accessors)
          putAll(injectors)
        }

        realGraph.seal(
          roots = roots,
          keep = extraKeeps,
          shrinkUnusedBindings = metroContext.options.shrinkUnusedBindings,
          onPopulated = {
            writeDiagnostic(
              "keys-populated",
              "${node.metroGraphOrFail.classIdOrFail.safePathString}.txt",
            ) {
              val keys = mutableListOf<IrTypeKey>()
              realGraph.bindings.forEachKey(keys::add)
              keys.sorted().joinToString("\n")
            }
          },
          onSortedCycle = { elementsInCycle ->
            writeDiagnostic(
              "cycle",
              "${node.metroGraphOrFail.classIdOrFail.safePathString}-${elementsInCycle[0].render(short = true, includeQualifier = false)}.txt",
            ) {
              elementsInCycle.plus(elementsInCycle[0]).joinToString("\n")
            }
          },
          validateBindings = ::validateBindings,
        )
      }

    val sortedKeys = topologyResult.sortedKeys
    val deferredTypes = topologyResult.deferredTypes
    val reachableKeys = topologyResult.reachableKeys

    if (hasErrors) {
      // Clear out the binding lookup now that we're done
      _bindingLookup = null
      return BindingGraphResult.ERROR
    }

    writeDiagnostic("keys-validated", "${node.metroGraphOrFail.classIdOrFail.safePathString}.txt") {
      sortedKeys.joinToString(separator = "\n")
    }

    writeDiagnostic("keys-deferred", "${node.metroGraphOrFail.classIdOrFail.safePathString}.txt") {
      deferredTypes.joinToString(separator = "\n")
    }

    trace("check empty multibindings") { checkEmptyMultibindings(onError) }
    trace("check for absent bindings") {
      check(!realGraph.bindings.any { _, v -> v is IrBinding.Absent }) {
        "Found absent bindings in the binding graph: ${dumpGraph("Absent bindings", short = true)}"
      }
    }

    // Only report unused keys that were explicitly declared in this graph
    // Only relevant if shrinkUnusedBindings is enabled
    val unusedKeys: Map<IrTypeKey, IrBinding.BoundInstance?>
    if (options.shrinkUnusedBindings) {
      val declaredKeys = bindingLookup.getDeclaredKeys()
      val unused = declaredKeys - reachableKeys
      if (unused.isNotEmpty()) {
        val unusedMultibindingElements =
          unused.filterToSet { it.multibindingBindingElementId != null }
        if (unusedMultibindingElements.isNotEmpty()) {
          val allMultibindings by memoize {
            buildList {
                realGraph.bindings.forEachValue { b -> if (b is IrBinding.Multibinding) add(b) }
                addAll(bindingLookup.getAvailableMultibindings().values)
              }
              .distinctBy { it.typeKey }
          }
          for ((key, binding) in bindingLookup.getAvailableMultibindings()) {
            if (binding.declaration != null) continue // Skip explicitly declared
            val unusedSources = binding.sourceBindings.intersect(unusedMultibindingElements)
            if (unusedSources.isEmpty()) continue

            // Report the first few bindings
            val message = buildString {
              appendLine(
                "[Metro/SuspiciousUnusedMultibinding] Synthetic multibinding " +
                  "${key.renderForDiagnostic(short = false)} is unused but has " +
                  "${binding.sourceBindings.size} source binding(s). " +
                  "Did you possibly bind them to the wrong type?"
              )
              appendLine()
              for (source in unusedSources.take(MAX_SUSPICIOUS_UNUSED_MULTIBINDINGS_TO_REPORT)) {
                val binding = bindingLookup[source] ?: continue
                val location = binding.renderLocationDiagnostic()
                appendLine("  ${location.location}")
                location.description?.let { appendLine(it.prependIndent("    ")) }
              }
              if (unusedSources.size > MAX_SUSPICIOUS_UNUSED_MULTIBINDINGS_TO_REPORT) {
                appendLine(
                  "  ...and ${unusedSources.size - MAX_SUSPICIOUS_UNUSED_MULTIBINDINGS_TO_REPORT} more"
                )
              }

              appendSimilarMultibindingHints(binding, allMultibindings)
            }

            reportCompat(node.sourceGraph, MetroDiagnostics.SUSPICIOUS_UNUSED_MULTIBINDING, message)
          }
        }
        writeDiagnostic(
          "keys-unused",
          "${node.metroGraphOrFail.classIdOrFail.safePathString}.txt",
        ) {
          unused.sorted().joinToString(separator = "\n")
        }
      }

      unusedKeys =
        unused.associateWith { key ->
          val binding = bindingLookup[key]
          if (binding is IrBinding.BoundInstance && binding.isGraphInput) {
            binding
          } else {
            null
          }
        }
    } else {
      unusedKeys = emptyMap()
    }

    val shardGroups =
      trace("compute shard groups") {
        val maxPerShard = metroContext.options.keysPerGraphShard
        val enableSharding = metroContext.options.enableGraphSharding
        if (enableSharding && topologyResult.adjacency.forward.size > maxPerShard) {
          topologyResult.partitionBySCCs(maxPerShard)
        } else {
          null
        }
      }

    // Clear out the binding lookup now that we're done
    _bindingLookup = null

    return BindingGraphResult(
      sortedKeys = sortedKeys,
      deferredTypes = deferredTypes,
      reachableKeys = reachableKeys,
      shardGroups = shardGroups,
      unusedKeys = unusedKeys,
      hasErrors = false,
    )
  }

  fun reportDuplicateBindings(
    key: IrTypeKey,
    bindings: List<IrBinding>,
    bindingStack: IrBindingStack,
  ) {
    realGraph.reportDuplicateBindings(key, bindings, bindingStack)
  }

  private fun checkEmptyMultibindings(onError: (List<GraphError>) -> Unit) {
    val multibindings = buildList {
      realGraph.bindings.forEachValue { binding ->
        if (binding is IrBinding.Multibinding) {
          add(binding)
        }
      }
    }
    // Get all registered multibindings for similarity checking
    val allMultibindings by memoize {
      (multibindings + bindingLookup.getAvailableMultibindings().values).distinctBy { it.typeKey }
    }
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

          appendSimilarMultibindingHints(multibinding, allMultibindings)
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
    multibinding: IrBinding.Multibinding,
    multibindings: List<IrBinding.Multibinding>,
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

  private fun Appendable.appendSimilarMultibindingHints(
    multibinding: IrBinding.Multibinding,
    allMultibindings: List<IrBinding.Multibinding>,
  ) {
    val similarBindings = findSimilarMultibindings(multibinding, allMultibindings).toList()
    if (similarBindings.isNotEmpty()) {
      appendLine()
      appendLine("Similar multibindings:")
      val reported = mutableSetOf<IrTypeKey>()
      for (key in similarBindings) {
        if (key in reported) continue
        appendLine("- ${key.renderForDiagnostic(short = true)}")
        reported += key
      }
    }
  }

  private fun missingBindingHints(key: IrTypeKey): List<String> {
    return buildList {
      if (key.type.hasErrorTypes()) {
        add(
          "Binding '${key.render(short = false, includeQualifier = true)}' is an error type and appears to be missing from the compile classpath."
        )
        return@buildList
      }

      if (key in bindingLookup.getParentGraphPrivateKeys()) {
        add(
          "A binding for '${key.renderForDiagnostic(short = true)}' exists in a parent graph but is marked " +
            "@GraphPrivate and cannot be accessed from this graph."
        )
      }

      bindingLookup.skippedDirectMapRequests[key]?.let { requestedContextKey ->
        bindingLookup.getAvailableBindings()[key]?.let { directBinding ->
          // Use rawType to render with Provider/Lazy wrapping preserved, since
          // IrContextualTypeKey.render() delegates to WrappedType.Map.render() which
          // renders the canonical map type without value wrapping.
          // Short render here since we already have the full render mentioned earlier in the trace
          val requestedType =
            requestedContextKey.rawType?.render(short = true)
              ?: requestedContextKey.render(short = true, includeQualifier = false)
          add(
            buildString {
              appendLine(
                "A directly-provided '${key.render(short = true, includeQualifier = false)}' binding exists, but direct Map bindings cannot satisfy '$requestedType' requests."
              )
              appendLine()
              val locationDiagnostic =
                directBinding.renderLocationDiagnostic(underlineTypeKey = true)
              appendLine("    ${locationDiagnostic.location}")
              locationDiagnostic.description?.let { appendLine(it.prependIndent("        ")) }
              appendLine()
              append(
                "Provider/Lazy-wrapped map values (e.g., Map<K, Provider<V>>) only work with a Map **multibinding** created with `@IntoMap` or `@Multibinds`."
              )
            }
          )
        }
      }
    }
  }

  private fun findSimilarBindings(key: IrTypeKey): Map<IrTypeKey, SimilarBinding> {
    // Use a map to avoid reporting duplicates
    val similarBindings = mutableMapOf<IrTypeKey, SimilarBinding>()

    // Error types we can't do anything with
    if (key.type.hasErrorTypes()) {
      return similarBindings
    }

    // Same type with different qualifier
    if (key.qualifier != null) {
      findBinding(key.copy(qualifier = null), allowLookup = true)?.let {
        similarBindings.putIfAbsent(
          it.typeKey,
          SimilarBinding(it.typeKey, it, "Different qualifier"),
        )
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
    findBinding(equivalentKey, allowLookup = true)?.let { similarBinding ->
      val nullabilityDescription = buildString {
        if (isNullable) {
          append("Non-nullable equivalent")
        } else {
          append("Nullable equivalent")
        }

        if (isNullable && similarBinding is IrBinding.ConstructorInjected) {
          append(
            ". Constructor-injected classes cannot implicitly satisfy nullable versions. Explicitly bind this type with `@Binds` separately if you want to use it for nullable bindings"
          )
        }
      }
      similarBindings.putIfAbsent(
        similarBinding.typeKey,
        SimilarBinding(similarBinding.typeKey, similarBinding, nullabilityDescription),
      )
    }

    // Merge graph bindings and cached bindings from BindingLookup
    val allBindings = buildMap {
      realGraph.bindings.forEach { key, value -> put(key, value) }
      // Add cached bindings that aren't already in the graph
      for ((bindingKey, binding) in bindingLookup.getAvailableBindings()) {
        @Suppress("RETURN_VALUE_NOT_USED") putIfAbsent(bindingKey, binding)
      }
      // Add multibindings that have been registered
      for ((bindingKey, binding) in bindingLookup.getAvailableMultibindings()) {
        @Suppress("RETURN_VALUE_NOT_USED") putIfAbsent(bindingKey, binding)
      }
    }

    // Iterate through all bindings to find similar ones
    for ((bindingKey, binding) in allBindings) {
      if (bindingKey.type.hasErrorTypes()) {
        reportCompilerBug("Unexpected error type in all bindings")
      } else if (bindingKey.multibindingKeyData != null) {
        // Multibinding entry, don't look at this at all
        continue
      }

      when {
        bindingKey.type == key.type && key.qualifier != bindingKey.qualifier -> {
          @Suppress("RETURN_VALUE_NOT_USED")
          similarBindings.putIfAbsent(
            bindingKey,
            SimilarBinding(bindingKey, binding, "Different qualifier"),
          )
        }

        binding is IrBinding.Multibinding -> {
          val valueType =
            if (binding.isSet) {
              (bindingKey.type.type as IrSimpleType).arguments[0].typeOrFail
            } else {
              // Map binding
              (bindingKey.type.type as IrSimpleType).arguments[1].typeOrFail
            }
          if (valueType == key.type) {
            @Suppress("RETURN_VALUE_NOT_USED")
            similarBindings.putIfAbsent(
              bindingKey,
              SimilarBinding(bindingKey, binding, "Multibinding"),
            )
          }
        }

        bindingKey.type == key.type -> {
          // Already covered above but here to avoid falling through to the subtype checks
          // below as they would always return true for this
        }

        bindingKey.type.isSubtypeOf(key.type, metroContext.irTypeSystemContext) -> {
          @Suppress("RETURN_VALUE_NOT_USED")
          similarBindings.putIfAbsent(bindingKey, SimilarBinding(bindingKey, binding, "Subtype"))
        }

        !bindingKey.type.isAny() &&
          key.type.type.isSubtypeOf(bindingKey.type, metroContext.irTypeSystemContext) -> {
          @Suppress("RETURN_VALUE_NOT_USED")
          similarBindings.putIfAbsent(bindingKey, SimilarBinding(bindingKey, binding, "Supertype"))
        }
      }
    }

    // Does the class exist but is internal with a @Contributes annotation?
    key.type.rawTypeOrNull()?.let { klass ->
      // Ask contribution data if it's there but not visible
      val contributingClass =
        node.aggregationScopes
          .flatMap { scope ->
            contributionData.findVisibleContributionClassesForScopeInHints(
              scope,
              includeNonFriendInternals = true,
              callingDeclaration = node.sourceGraph,
            )
          }
          .find { contribution ->
            val implementsKey = contribution.implements(klass.classId!!)
            val bindsKey =
              contribution
                .annotationsIn(metroContext.metroSymbols.classIds.allContributesAnnotations)
                .any {
                  val boundType = it.bindingTypeOrNull().first?.rawTypeOrNull()?.classId
                  boundType == null || boundType == klass.classId
                }
            implementsKey && bindsKey
          }

      if (contributingClass != null) {
        @Suppress("RETURN_VALUE_NOT_USED")
        similarBindings.putIfAbsent(
          key,
          SimilarBinding(
            typeKey = key,
            binding = null,
            description =
              "Contributed by '${contributingClass.kotlinFqName.asString()}' but that class is internal to its module and its module is not a friend module to this one.",
          ),
        )
      }
    }

    return similarBindings.filterNot {
      (it.value.binding as? IrBinding.BindingWithAnnotations)?.annotations?.isIntoMultibinding ==
        true
    }
  }

  // TODO iterate on this more!
  internal fun dumpGraph(name: String, short: Boolean): String {
    if (realGraph.bindings.isEmpty()) return "Empty binding graph"

    return buildString {
      append("Binding Graph: ")
      appendLine(name)
      // Sort by type key for consistent output
      realGraph.bindings
        .asMap()
        .entries
        .sortedBy { it.key.toString() }
        .forEach { (_, binding) ->
          appendLine("─".repeat(50))
          appendBinding(binding, short, isNested = false)
        }
    }
  }

  /**
   * We always want to report the original declaration for overridable nodes, as fake overrides
   * won't necessarily have source that is reportable.
   */
  @Suppress("UNCHECKED_CAST")
  private fun <T : IrDeclaration> T.originalDeclarationIfOverride(): T {
    return when (this) {
      is IrValueParameter -> {
        val index = indexInParameters
        // Need to check if the parent is a fakeOverride function or property setter
        val parent = parent.expectAs<IrFunction>()
        val originalParent = parent.originalDeclarationIfOverride()
        originalParent.parameters[index] as T
      }
      is IrSimpleFunction if isFakeOverride -> {
        overriddenSymbolsSequence().last().owner as T
      }
      is IrProperty if isFakeOverride -> {
        overriddenSymbolsSequence().last().owner as T
      }
      else -> this
    }
  }

  private fun onError(message: String, stack: IrBindingStack) {
    val declaration =
      stack.lastEntryOrGraph?.originalDeclarationIfOverride()
        ?: node.reportableSourceGraphDeclaration
    onError(message, declaration)
  }

  private fun onError(message: String, declaration: IrDeclaration) {
    hasErrors = true
    metroContext.reportCompat(declaration, MetroDiagnostics.METRO_ERROR, message)
  }

  private fun onError(message: String, element: IrElement) {
    hasErrors = true
    if (element is IrDeclaration) {
      onError(message, element)
    } else {
      metroContext.diagnosticReporter
        .at(element, node.metroGraphOrFail.file)
        .report(MetroDiagnostics.METRO_ERROR, message)
    }
  }

  private fun validateBindings(
    bindings: ScatterMap<IrTypeKey, IrBinding>,
    stack: IrBindingStack,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: GraphAdjacency<IrTypeKey>,
  ) {
    val rootsByTypeKey = roots.mapKeys { it.key.typeKey }
    bindings.forEachValue { binding ->
      checkScope(binding, stack, roots, adjacency.forward)
      validateMultibindings(binding, bindings, roots, adjacency.forward)
      validateAssistedInjection(binding, bindings, rootsByTypeKey, adjacency.reverse)
    }
  }

  // Check scoping compatibility
  private fun checkScope(
    binding: IrBinding,
    stack: IrBindingStack,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    val bindingScope = binding.scope
    // Our binding doesn't have a scope... so we don't care about scopes
    if (bindingScope == null) return
    // Our binding does have a scope... and it's compatible with our node yay
    if (bindingScope in node.scopes) return

    // Error if there are mismatched scopes

    // Does bindingScope have the same toString? Annoying! Let's disambiguate
    val bindingScopeStr =
      "$bindingScope".takeIf { it !in node.scopes.map { "$it" } }
        ?: bindingScope.render(short = false)

    val nodeScopesStrs =
      node.scopes.map { "$it".takeIf { it != "$bindingScope" } ?: it.render(short = false) }

    val isUnscoped = node.scopes.isEmpty()
    val declarationToReport = node.sourceGraph.sourceGraphIfMetroGraph
    val stack = buildStackToRoot(binding.typeKey, roots, adjacency, stack)
    stack.push(
      IrBindingStack.Entry.simpleTypeRef(
        binding.contextualTypeKey,
        usage = "(scoped to '$bindingScopeStr')",
      )
    )
    val message = buildString {
      append("[Metro/IncompatiblyScopedBindings] ")
      append(node.sourceGraph.kotlinFqName)
      if (isUnscoped) {
        // Unscoped graph but scoped binding
        append(" (unscoped) may not reference scoped bindings:")
      } else {
        // Scope mismatch
        append(
          " (scopes ${nodeScopesStrs.joinToString { "'$it'" }}) may not reference bindings from different scopes:"
        )
      }
      appendLine()
      appendBindingStack(stack, short = false)

      if (node.sourceGraph.origin == Origins.GeneratedGraphExtension) {
        val sourceGraphFqName = node.sourceGraph.sourceGraphIfMetroGraph.kotlinFqName
        val receivingGraphFqName =
          // Find the actual parent/receiving graph
          node.parentGraph?.sourceGraph?.sourceGraphIfMetroGraph?.kotlinFqName
            ?: declarationToReport.sourceGraphIfMetroGraph.kotlinFqName

        // Only show the hint if the source and receiving graphs are actually different
        if (sourceGraphFqName != receivingGraphFqName) {
          appendLine()
          appendLine()
          appendLine("(Hint)")
          append(
            "${node.sourceGraph.name} is contributed by '${sourceGraphFqName}' to '${receivingGraphFqName}'."
          )
        }
      }
    }
    metroContext.reportCompat(declarationToReport, MetroDiagnostics.METRO_ERROR, message)
  }

  private fun buildStackToRoot(
    typeKey: IrTypeKey,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
    stack: IrBindingStack = newBindingStack(),
  ): IrBindingStack {
    val backTrace = buildRouteToRoot(typeKey, roots, adjacency)
    for (entry in backTrace) {
      stack.push(entry)
    }
    return stack
  }

  private fun validateMultibindings(
    binding: IrBinding,
    bindings: ScatterMap<IrTypeKey, IrBinding>,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    if (binding !is IrBinding.Multibinding) return
    if (!binding.isMap) return
    val keysWithDupes =
      binding.sourceBindings
        .mapNotNull { bindings[it] }
        .filterIsInstance<IrBinding.BindingWithAnnotations>()
        .groupBy { it.annotations.mapKey }
        .filterValues { it.size > 1 }

    for ((mapKey, dupes) in keysWithDupes) {
      if (mapKey == null) {
        reportCompilerBug("Map key should not be null for map multibindings")
      }

      val stack = buildStackToRoot(binding.typeKey, roots, adjacency)

      val message = buildString {
        appendLine(
          """
            [Metro/DuplicateMapKeys] Duplicate map keys found for multibinding '${binding.typeKey.renderForDiagnostic(short = false)}'.
            The following bindings contribute the same map key '${mapKey.render(short = false)}':
          """
            .trimIndent()
        )
        appendLine()
        for (dupe in dupes) {
          val locationDiagnostic =
            dupe.renderLocationDiagnostic(
              shortLocation = MetroOptions.SystemProperties.SHORTEN_LOCATIONS,
              underlineTypeKey = false,
            )
          appendLine("    ${locationDiagnostic.location}")
          locationDiagnostic.description?.let { appendLine(it.prependIndent("        ")) }
        }
        appendLine()
        appendBindingStack(stack)
      }
      onError(message, stack)
    }
  }

  // TODO can this check move to FIR injection sites?
  private fun validateAssistedInjection(
    binding: IrBinding,
    bindings: ScatterMap<IrTypeKey, IrBinding>,
    roots: Map<IrTypeKey, IrBindingStack.Entry>,
    reverseAdjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    if (binding !is IrBinding.ConstructorInjected || !binding.isAssisted) return

    fun reportInvalidBinding(declaration: IrDeclarationWithName?) {
      // Look up the assisted factory as a hint
      val assistedFactory =
        bindings
          .asMap()
          .values
          .find { it is IrBinding.AssistedFactory && it.targetBinding.typeKey == binding.typeKey }
          ?.typeKey
          // Check in the class itself for @AssistedFactory
          ?: binding.typeKey.type.rawTypeOrNull()?.let { rawType ->
            rawType.nestedClasses
              .firstOrNull { nestedClass ->
                nestedClass.isAnnotatedWithAny(
                  metroContext.metroSymbols.classIds.assistedFactoryAnnotations
                )
              }
              ?.let { IrTypeKey(it.defaultType) }
          }
      // Report an error for anything that isn't an assisted binding depending on this
      val message = buildString {
        append("[Metro/InvalidBinding] ")
        append(
          "'${binding.typeKey}' uses assisted injection and cannot be injected directly into '${declaration?.fqNameWhenAvailable}'. You must inject a corresponding @AssistedFactory type instead."
        )
        if (assistedFactory != null) {
          appendLine()
          appendLine()
          appendLine("(Hint)")
          appendLine(
            "It looks like the @AssistedFactory for '${binding.typeKey}' is '${assistedFactory}'."
          )
        }
      }
      onError(message, declaration ?: node.sourceGraph)
    }

    reverseAdjacency[binding.typeKey]?.let { dependents ->
      for (dependentKey in dependents) {
        val dependentBinding = bindings[dependentKey] ?: continue
        if (dependentBinding !is IrBinding.AssistedFactory) {
          reportInvalidBinding(
            dependentBinding.parameters.allParameters
              .find { it.typeKey == binding.typeKey }
              ?.ir
              ?.takeIf {
                val location = it.locationOrNull() ?: return@takeIf false
                location.line != 0 || location.column != 0
              } ?: dependentBinding.reportableDeclaration
          )
        }
      }
    }
    roots[binding.typeKey]?.let { reportInvalidBinding(it.declaration) }
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
    // No need to walk through the tree without roots
    if (roots.isEmpty()) return emptyList()

    // Build who depends on what
    val dependents = mutableMapOf<IrTypeKey, MutableSet<IrTypeKey>>()
    for ((key, deps) in adjacency) {
      for (dep in deps) {
        dependents.getAndAdd(dep, key)
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

  private fun Appendable.appendBinding(binding: IrBinding, short: Boolean, isNested: Boolean) {
    appendLine("Type: ${binding.typeKey.renderForDiagnostic(short)}")
    appendLine("├─ Binding: ${binding::class.simpleName}")
    appendLine("├─ Contextual Type: ${binding.contextualTypeKey.render(short)}")

    binding.scope?.let { scope -> appendLine("├─ Scope: $scope") }

    if (binding is IrBinding.Alias) {
      appendLine("├─ Aliased type: ${binding.aliasedType.renderForDiagnostic(short)}")
    }

    if (binding.parameters.allParameters.isNotEmpty()) {
      appendLine("├─ Dependencies:")
      binding.parameters.allParameters.forEach { param ->
        appendLine("│  ├─ ${param.typeKey.renderForDiagnostic(short)}")
        appendLine("│  │  └─ Parameter: ${param.name} (${param.contextualTypeKey.render(short)})")
      }
    }

    if (!isNested && binding is IrBinding.Multibinding && binding.sourceBindings.isNotEmpty()) {
      appendLine("├─ Source bindings:")
      binding.sourceBindings.forEach { sourceBindingKey ->
        val sourceBinding = requireBinding(sourceBindingKey)
        val nested = buildString { appendBinding(sourceBinding, short, isNested = true) }
        append("│  ├─ ")
        appendLine(nested.lines().first())
        appendLine(nested.lines().drop(1).joinToString("\n").prependIndent("│  │  "))
      }
    }

    binding.reportableDeclaration?.locationOrNull()?.let { location ->
      appendLine("└─ Location: ${location.render(short)}")
    }
  }

  data class SimilarBinding(
    val typeKey: IrTypeKey,
    val binding: IrBinding?,
    val description: String,
  ) {
    fun render(short: Boolean): String {
      return buildString {
        append(typeKey.renderForDiagnostic(short = short))
        append(" (")
        append(description)
        append(")")
        binding?.let {
          append(". Type: ")
          append(binding.javaClass.simpleName)
          append('.')
          binding.reportableDeclaration?.renderSourceLocation(short = short)?.let {
            append(" Source: ")
            append(it)
          }
        }
      }
    }
  }
}
