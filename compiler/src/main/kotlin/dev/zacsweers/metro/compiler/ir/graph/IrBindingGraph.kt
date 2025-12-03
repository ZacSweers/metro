// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.graph.MissingBindingHints
import dev.zacsweers.metro.compiler.graph.MutableBindingGraph
import dev.zacsweers.metro.compiler.graph.partitionBySCCs
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.bindingTypeOrNull
import dev.zacsweers.metro.compiler.ir.hasErrorTypes
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.locationOrNull
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireMapKeyType
import dev.zacsweers.metro.compiler.ir.requireMapValueType
import dev.zacsweers.metro.compiler.ir.requireSetElementType
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses

internal class IrBindingGraph(
  metroContext: IrMetroContext,
  private val node: DependencyGraphNode,
  newBindingStack: () -> IrBindingStack,
  private val bindingLookup: BindingLookup,
  private val contributionData: IrContributionData,
) : IrMetroContext by metroContext {
  private var hasErrors = false
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
      computeBindings = { contextKey, currentBindings, stack ->
        bindingLookup.lookup(contextKey, currentBindings, stack)
      },
      onError = ::onError,
      onHardError = { message, stack ->
        onError(message, stack)
        exitProcessing()
      },
      missingBindingHints = { key, stack ->
        MissingBindingHints(
          missingBindingHints(key, stack),
          findSimilarBindings(key).mapValues { it.value.render(short = true) },
        )
      },
    )

  // TODO hoist accessors up and visit in seal?
  private val accessors = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  private val injectors = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  private val extraKeeps = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  private val reservedProperties = mutableMapOf<IrTypeKey, ParentContext.PropertyAccess>()

  // Thin immutable view over the internal bindings
  fun bindingsSnapshot(): Map<IrTypeKey, IrBinding> = realGraph.bindings

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

  fun reserveProperty(key: IrTypeKey, access: ParentContext.PropertyAccess) {
    reservedProperties[key] = access
  }

  fun reservedProperty(key: IrTypeKey): ParentContext.PropertyAccess? = reservedProperties[key]

  fun findBinding(key: IrTypeKey): IrBinding? = realGraph[key]

  // For bindings we expect to already be cached
  fun requireBinding(key: IrTypeKey): IrBinding {
    return requireBinding(IrContextualTypeKey.create(key))
  }

  fun requireBinding(contextKey: IrContextualTypeKey): IrBinding {
    return realGraph[contextKey.typeKey]
      ?: run {
        if (contextKey.hasDefault) return IrBinding.Absent(contextKey.typeKey)
        exitProcessing()
      }
  }

  operator fun contains(key: IrTypeKey): Boolean = key in realGraph

  data class BindingGraphResult(
    val sortedKeys: List<IrTypeKey>,
    val deferredTypes: Set<IrTypeKey>,
    val reachableKeys: Set<IrTypeKey>,
    val shardGroups: List<List<IrTypeKey>>?,
    val hasErrors: Boolean,
  )

  data class GraphError(val declaration: IrDeclaration?, val message: String)

  fun seal(parentTracer: Tracer, onError: (List<GraphError>) -> Unit): BindingGraphResult {
    val topologyResult =
      parentTracer.traceNested("seal graph") { tracer ->
        val roots = buildMap {
          putAll(accessors)
          putAll(injectors)
        }

        realGraph.seal(
          roots = roots,
          keep = extraKeeps,
          shrinkUnusedBindings = metroContext.options.shrinkUnusedBindings,
          tracer = tracer,
          onPopulated = {
            writeDiagnostic("keys-populated-${parentTracer.tag}.txt") {
              realGraph.bindings.keys.sorted().joinToString("\n")
            }
          },
          onSortedCycle = { elementsInCycle ->
            writeDiagnostic(
              "cycle-${parentTracer.tag}-${elementsInCycle[0].render(short = true, includeQualifier = false)}.txt"
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
      return BindingGraphResult(emptyList(), emptySet(), emptySet(), emptyList(), true)
    }

    writeDiagnostic("keys-validated-${parentTracer.tag}.txt") {
      sortedKeys.joinToString(separator = "\n")
    }

    writeDiagnostic("keys-deferred-${parentTracer.tag}.txt") {
      deferredTypes.joinToString(separator = "\n")
    }

    val unused = bindingsSnapshot().keys - reachableKeys
    if (unused.isNotEmpty()) {
      // TODO option to warn or fail? What about extensions that implicitly have many unused
      writeDiagnostic("keys-unused-${parentTracer.tag}.txt") {
        unused.joinToString(separator = "\n")
      }
    }

    parentTracer.traceNested("check empty multibindings") { checkEmptyMultibindings(onError) }
    parentTracer.traceNested("check for absent bindings") {
      check(realGraph.bindings.values.none { it is IrBinding.Absent }) {
        "Found absent bindings in the binding graph: ${dumpGraph("Absent bindings", short = true)}"
      }
    }

    val shardGroups =
      parentTracer.traceNested("compute shard groups") {
        val maxPerShard = metroContext.options.keysPerGraphShard
        val enableSharding = metroContext.options.enableGraphSharding
        if (enableSharding && topologyResult.adjacency.size > maxPerShard) {
          topologyResult.partitionBySCCs(maxPerShard)
        } else {
          null
        }
      }
    return BindingGraphResult(sortedKeys, deferredTypes, reachableKeys, shardGroups, false)
  }

  fun reportDuplicateBinding(
    key: IrTypeKey,
    existing: IrBinding,
    duplicate: IrBinding,
    bindingStack: IrBindingStack,
  ) {
    realGraph.reportDuplicateBinding(key, existing, duplicate, bindingStack)
  }

  private fun checkEmptyMultibindings(onError: (List<GraphError>) -> Unit) {
    val multibindings = realGraph.bindings.values.filterIsInstance<IrBinding.Multibinding>()
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

          val similarBindings = findSimilarMultibindings(multibinding, allMultibindings).toList()
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

  private fun missingBindingHints(key: IrTypeKey, stack: IrBindingStack): List<String> {
    return buildList {
      if (key.type.hasErrorTypes()) {
        add(
          "Binding '${key.render(short = false, includeQualifier = true)}' is an error type and appears to be missing from the compile classpath."
        )
        return@buildList
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
      findBinding(key.copy(qualifier = null))?.let {
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
    findBinding(equivalentKey)?.let {
      val nullabilityDescription =
        if (isNullable) "Non-nullable equivalent" else "Nullable equivalent"
      similarBindings.putIfAbsent(
        it.typeKey,
        SimilarBinding(it.typeKey, it, nullabilityDescription),
      )
    }

    // Merge graph bindings and cached bindings from BindingLookup
    val allBindings = buildMap {
      putAll(realGraph.bindings)
      // Add cached bindings that aren't already in the graph
      for ((bindingKey, binding) in bindingLookup.getAvailableStaticBindings()) {
        putIfAbsent(bindingKey, binding)
      }
      // Add multibindings that have been registered
      for ((bindingKey, binding) in bindingLookup.getAvailableMultibindings()) {
        putIfAbsent(bindingKey, binding)
      }
    }

    // Iterate through all bindings to find similar ones
    for ((bindingKey, binding) in allBindings) {
      if (bindingKey.type.hasErrorTypes()) {
        error("wtf")
      }
      when {
        bindingKey.type == key.type && key.qualifier != bindingKey.qualifier -> {
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
          similarBindings.putIfAbsent(bindingKey, SimilarBinding(bindingKey, binding, "Subtype"))
        }

        key.type.type.isSubtypeOf(bindingKey.type, metroContext.irTypeSystemContext) -> {
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
      realGraph.bindings.entries
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
        return originalParent.parameters[index] as T
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
    hasErrors = true
    val declaration =
      stack.lastEntryOrGraph?.originalDeclarationIfOverride()
        ?: node.reportableSourceGraphDeclaration
    metroContext.reportCompat(declaration, MetroDiagnostics.METRO_ERROR, message)
  }

  private fun validateBindings(
    bindings: Map<IrTypeKey, IrBinding>,
    stack: IrBindingStack,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    val reverseAdjacency = buildReverseAdjacency(adjacency)
    val rootsByTypeKey = roots.mapKeys { it.key.typeKey }
    for (binding in bindings.values) {
      checkScope(binding, stack, roots, adjacency)
      validateAssistedInjection(binding, bindings, rootsByTypeKey, reverseAdjacency)
    }
  }

  private fun buildReverseAdjacency(
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>
  ): Map<IrTypeKey, Set<IrTypeKey>> {
    val reverse = mutableMapOf<IrTypeKey, MutableSet<IrTypeKey>>()
    for ((from, tos) in adjacency) {
      for (to in tos) {
        reverse.getOrPut(to) { mutableSetOf() }.add(from)
      }
    }
    return reverse
  }

  // Check scoping compatibility
  private fun checkScope(
    binding: IrBinding,
    stack: IrBindingStack,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    val bindingScope = binding.scope
    if (bindingScope != null) {
      if (node.scopes.isEmpty() || bindingScope !in node.scopes) {
        val isUnscoped = node.scopes.isEmpty()
        // Error if there are mismatched scopes
        val declarationToReport = node.sourceGraph.sourceGraphIfMetroGraph
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
          append(node.sourceGraph.kotlinFqName)
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

          if (node.sourceGraph.origin == Origins.GeneratedGraphExtension) {
            val sourceGraphFqName = node.sourceGraph.sourceGraphIfMetroGraph.kotlinFqName
            val receivingGraphFqName =
              // Find the actual parent/receiving graph - it should be in extendedGraphNodes
              node.extendedGraphNodes.values
                .firstOrNull()
                ?.sourceGraph
                ?.sourceGraphIfMetroGraph
                ?.kotlinFqName ?: declarationToReport.sourceGraphIfMetroGraph.kotlinFqName

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
    }
  }

  // TODO can this check move to FIR injection sites?
  private fun validateAssistedInjection(
    binding: IrBinding,
    bindings: Map<IrTypeKey, IrBinding>,
    roots: Map<IrTypeKey, IrBindingStack.Entry>,
    reverseAdjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    if (binding !is IrBinding.ConstructorInjected || !binding.isAssisted) return

    fun reportInvalidBinding(declaration: IrDeclarationWithName?) {
      // Look up the assisted factory as a hint
      val assistedFactory =
        bindings.values
          .find { it is IrBinding.Assisted && it.target.typeKey == binding.typeKey }
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
      metroContext.reportCompat(
        declaration ?: node.sourceGraph,
        MetroDiagnostics.METRO_ERROR,
        message,
      )
    }

    reverseAdjacency[binding.typeKey]?.let { dependents ->
      for (dependentKey in dependents) {
        val dependentBinding = bindings[dependentKey] ?: continue
        if (dependentBinding !is IrBinding.Assisted) {
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

  private fun Appendable.appendBinding(binding: IrBinding, short: Boolean, isNested: Boolean) {
    appendLine("Type: ${binding.typeKey.render(short)}")
    appendLine("├─ Binding: ${binding::class.simpleName}")
    appendLine("├─ Contextual Type: ${binding.contextualTypeKey.render(short)}")

    binding.scope?.let { scope -> appendLine("├─ Scope: $scope") }

    if (binding is IrBinding.Alias) {
      appendLine("├─ Aliased type: ${binding.aliasedType.render(short)}")
    }

    if (binding.parameters.allParameters.isNotEmpty()) {
      appendLine("├─ Dependencies:")
      binding.parameters.allParameters.forEach { param ->
        appendLine("│  ├─ ${param.typeKey.render(short)}")
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
        append(typeKey.render(short = short))
        append(" (")
        append(description)
        append(")")
        binding?.let {
          append(". Type: ")
          append(binding.javaClass.simpleName)
          append('.')
          binding.reportableDeclaration?.locationOrNull()?.render(short)?.let {
            append(" Source: ")
            append(it)
          }
        }
      }
    }
  }
}
