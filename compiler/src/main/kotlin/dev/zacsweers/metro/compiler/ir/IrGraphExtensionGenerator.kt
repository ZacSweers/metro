// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.name.ClassId

internal class IrGraphExtensionGenerator(
  context: IrMetroContext,
  private val contributionData: IrContributionData,
  private val parentGraph: IrClass,
) : IrMetroContext by context {

  /**
   * Cache for transitive closure of all included binding containers. Maps [ClassId] ->
   * [Set<IrClass>][BindingContainer] where the values represent all transitively included binding
   * containers starting from the given [ClassId].
   */
  private val transitiveBindingContainerCache = mutableMapOf<ClassId, Set<IrClass>>()
  private val nameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val generatedClassesCache = mutableMapOf<CacheKey, IrClass>()

  private data class CacheKey(val typeKey: IrTypeKey, val parentGraph: ClassId)

  fun getOrBuildGraphExtensionImpl(
    typeKey: IrTypeKey,
    parentGraph: IrClass,
    contributedAccessor: MetroSimpleFunction,
    parentTracer: Tracer,
  ): IrClass {
    return generatedClassesCache.getOrPut(CacheKey(typeKey, parentGraph.classIdOrFail)) {
      val sourceSamFunction =
        contributedAccessor.ir
          .overriddenSymbolsSequence()
          .firstOrNull {
            it.owner.parentAsClass.isAnnotatedWithAny(
              symbols.classIds.graphExtensionFactoryAnnotations
            )
          }
          ?.owner ?: contributedAccessor.ir

      val parent = sourceSamFunction.parentClassOrNull ?: error("No parent class found")
      val isFactorySAM =
        parent.isAnnotatedWithAny(symbols.classIds.graphExtensionFactoryAnnotations)
      if (isFactorySAM) {
        generateImplFromFactory(sourceSamFunction, parentTracer, typeKey)
      } else {
        val returnType = contributedAccessor.ir.returnType.rawType()
        val returnIsGraphExtensionFactory =
          returnType.isAnnotatedWithAny(symbols.classIds.graphExtensionFactoryAnnotations)
        val returnIsGraphExtension =
          returnType.isAnnotatedWithAny(symbols.classIds.graphExtensionAnnotations)
        if (returnIsGraphExtensionFactory) {
          val samFunction =
            returnType.singleAbstractFunction().apply {
              remapTypes(sourceSamFunction.typeRemapperFor(contributedAccessor.ir.returnType))
            }
          generateImplFromFactory(samFunction, parentTracer, typeKey)
        } else if (returnIsGraphExtension) {
          // Simple case with no creator
          generateImpl(returnType, creatorFunction = null, typeKey)
        } else {
          error("Not a graph extension: ${returnType.kotlinFqName}")
        }
      }
    }
  }

  private fun generateImplFromFactory(
    factoryFunction: IrSimpleFunction,
    parentTracer: Tracer,
    typeKey: IrTypeKey
  ): IrClass {
    val sourceFactory = factoryFunction.parentAsClass
    val sourceGraph = sourceFactory.parentAsClass
    return parentTracer.traceNested("Generate graph extension ${sourceGraph.name}") {
      generateImpl(sourceGraph = sourceGraph, creatorFunction = factoryFunction, typeKey = typeKey)
    }
  }

  private fun generateImpl(sourceGraph: IrClass, creatorFunction: IrSimpleFunction?, typeKey: IrTypeKey): IrClass {
    val graphExtensionAnno =
      sourceGraph.annotationsIn(symbols.classIds.graphExtensionAnnotations).firstOrNull()
    val extensionAnno =
      graphExtensionAnno
        ?: error(
          "Expected @GraphExtension on ${sourceGraph.kotlinFqName}"
        )

    val sourceScope = extensionAnno.scopeClassOrNull()
    val scope = sourceScope?.classId
    val contributedSupertypes = mutableSetOf<IrType>()
    val contributedBindingContainers = mutableMapOf<ClassId, IrClass>()

    // Merge contributed types
    if (scope != null) {
      val additionalScopes =
        extensionAnno.additionalScopes().map { it.classType.rawType().classIdOrFail }

      val allScopes = (additionalScopes + scope).toSet()

      // Get all contributions and binding containers
      val allContributions =
        allScopes
          .flatMap { contributionData.getContributions(it) }
          .groupByTo(mutableMapOf()) {
            // For Metro contributions, we need to check the parent class ID
            // This is always the $$MetroContribution, the contribution's parent is the actual class
            it.rawType().classIdOrFail.parentClassId!!
          }

      contributedBindingContainers.putAll(
        allScopes
          .flatMap { contributionData.getBindingContainerContributions(it) }
          .associateByTo(mutableMapOf()) { it.classIdOrFail }
      )

      // TODO do we exclude directly contributed ones or also include transitives?

      // Process excludes
      val excluded = extensionAnno.excludedClasses()
      for (excludedClass in excluded) {
        val excludedClassId = excludedClass.classType.rawType().classIdOrFail

        // Remove excluded binding containers - they won't contribute their bindings
        contributedBindingContainers.remove(excludedClassId)

        // Remove contributions from excluded classes that have nested $$MetroContribution classes
        // (binding containers don't have these, so this only affects @ContributesBinding etc.)
        allContributions.remove(excludedClassId)
      }

      // Apply replacements from remaining (non-excluded) binding containers
      contributedBindingContainers.values.forEach { bindingContainer ->
        bindingContainer
          .annotationsIn(symbols.classIds.allContributesAnnotations)
          .flatMap { annotation -> annotation.replacedClasses() }
          .mapNotNull { replacedClass -> replacedClass.classType.rawType().classId }
          .forEach { replacedClassId -> allContributions.remove(replacedClassId) }
      }

      // Process rank-based replacements if Dagger-Anvil interop is enabled
      if (options.enableDaggerAnvilInterop) {
        val rankReplacements = processRankBasedReplacements(allScopes, allContributions)
        for (replacedClassId in rankReplacements) {
          allContributions.remove(replacedClassId)
        }
      }

      contributedSupertypes += allContributions.values.flatten()
    }

    // Source is a `@GraphExtension`-annotated class, we want to generate a header impl class
    val graphImpl =
      pluginContext.irFactory
        .buildClass {
          // Ensure a unique name
          name =
            nameAllocator
              .newName("${sourceGraph.name.asString().capitalizeUS()}${Symbols.StringNames.IMPL}")
              .asName()
          origin = Origins.GeneratedGraphExtension
          kind = ClassKind.CLASS
          isInner = true
        }
        .apply {
          createThisReceiverParameter()

          generatedGraphExtensionData = GeneratedGraphExtensionData(
            typeKey = typeKey
          )

          // Add a @DependencyGraph(...) annotation
          annotations +=
            buildAnnotation(symbol, symbols.metroDependencyGraphAnnotationConstructor) { annotation
              ->
              // scope
              sourceScope?.let { annotation.arguments[0] = kClassReference(it.symbol) }

              // additionalScopes
              extensionAnno.additionalScopes().copyToIrVararg()?.let {
                annotation.arguments[1] = it
              }

              // excludes
              extensionAnno.excludedClasses().copyToIrVararg()?.let { annotation.arguments[2] = it }

              // bindingContainers
              val allContainers = buildSet {
                val declaredContainers =
                  extensionAnno
                    .bindingContainerClasses(includeModulesArg = options.enableDaggerRuntimeInterop)
                    .map { it.classType.rawType() }
                addAll(declaredContainers)
                addAll(contributedBindingContainers.values)
              }
              allContainers.let(::resolveAllBindingContainersCached).toIrVararg()?.let {
                annotation.arguments[3] = it
              }
            }

          superTypes += sourceGraph.defaultType

          // Add only non-binding-container contributions as supertypes
          superTypes +=
            contributedSupertypes
              // Deterministic sort
              .sortedBy { it.rawType().classIdOrFail.toString() }
        }

    graphImpl
      .addConstructor {
        isPrimary = true
        origin = Origins.Default
        // This will be finalized in IrGraphGenerator
        isFakeOverride = true
      }
      .apply {
        // TODO generics?
        setDispatchReceiver(parentGraph.thisReceiverOrFail.copyTo(this, type = parentGraph.defaultType))
        // Copy over any creator params
        creatorFunction?.let {
          for (param in it.regularParameters) {
            addValueParameter(param.name, param.type).apply { this.copyAnnotationsFrom(param) }
          }
        }

        body = this.generateDefaultConstructorBody()
      }

    parentGraph.addChild(graphImpl)

    graphImpl.addFakeOverrides(irTypeSystemContext)

    return graphImpl
  }

  /**
   * This provides `ContributesBinding.rank` interop for users migrating from Dagger-Anvil to make
   * the migration to Metro more feasible.
   *
   * @return The bindings which have been outranked and should not be included in the merged graph.
   */
  private fun processRankBasedReplacements(
    allScopes: Set<ClassId>,
    contributions: Map<ClassId, List<IrType>>,
  ): Set<ClassId> {
    val pendingRankReplacements = mutableSetOf<ClassId>()

    val rankedBindings =
      contributions.values
        .flatten()
        .map { it.rawType().parentAsClass }
        .distinctBy { it.classIdOrFail }
        .flatMap { contributingType ->
          contributingType
            .annotationsIn(symbols.classIds.contributesBindingAnnotations)
            .mapNotNull { annotation ->
              val scope = annotation.scopeOrNull() ?: return@mapNotNull null
              if (scope !in allScopes) return@mapNotNull null

              val explicitBindingMissingMetadata =
                annotation.getValueArgument(Symbols.Names.binding)

              if (explicitBindingMissingMetadata != null) {
                // This is a case where an explicit binding is specified but we receive the argument
                // as FirAnnotationImpl without the metadata containing the type arguments so we
                // short-circuit since we lack the info to compare it against other bindings.
                null
              } else {
                val (explicitBindingType, ignoreQualifier) = annotation.bindingTypeOrNull()
                val boundType =
                  explicitBindingType
                    ?: contributingType.implicitBoundTypeOrNull()!! // Checked in FIR

                ContributedIrBinding(
                  contributingType = contributingType,
                  typeKey =
                    IrTypeKey(
                      boundType,
                      if (ignoreQualifier) null else contributingType.qualifierAnnotation(),
                    ),
                  rank = annotation.rankValue(),
                )
              }
            }
        }

    val bindingGroups =
      rankedBindings
        .groupBy { binding -> binding.typeKey }
        .filter { bindingGroup -> bindingGroup.value.size > 1 }

    for (bindingGroup in bindingGroups.values) {
      val topBindings =
        bindingGroup
          .groupBy { binding -> binding.rank }
          .toSortedMap()
          .let { it.getValue(it.lastKey()) }

      // These are the bindings that were outranked and should not be processed further
      bindingGroup.minus(topBindings).forEach {
        pendingRankReplacements += it.contributingType.classIdOrFail
      }
    }

    return pendingRankReplacements
  }

  /**
   * Resolves all binding containers transitively starting from the given roots. This method handles
   * caching and cycle detection to build the transitive closure of all included binding containers.
   */
  // TODO merge logic with BindingContainerTransformer's impl
  private fun resolveAllBindingContainersCached(roots: Set<IrClass>): Set<IrClass> {
    val result = mutableSetOf<IrClass>()
    val visitedClasses = mutableSetOf<ClassId>()

    for (root in roots) {
      val classId = root.classIdOrFail

      // Check if we already have this in cache
      transitiveBindingContainerCache[classId]?.let { cachedResult ->
        result.addAll(cachedResult)
        continue
      }

      // Compute transitive closure for this root
      val rootTransitiveClosure = computeTransitiveBindingContainers(root, visitedClasses)

      // Cache the result
      transitiveBindingContainerCache[classId] = rootTransitiveClosure
      result.addAll(rootTransitiveClosure)
    }

    return result
  }

  private fun computeTransitiveBindingContainers(
    root: IrClass,
    globalVisited: MutableSet<ClassId>,
  ): Set<IrClass> {
    val result = mutableSetOf<IrClass>()
    val localVisited = mutableSetOf<ClassId>()
    val queue = ArrayDeque<IrClass>()

    queue += root

    while (queue.isNotEmpty()) {
      val bindingContainerClass = queue.removeFirst()
      val classId = bindingContainerClass.classIdOrFail

      // Skip if we've already processed this class in any context
      if (classId in globalVisited || classId in localVisited) continue
      localVisited += classId
      globalVisited += classId

      // Check cache first for this specific class
      transitiveBindingContainerCache[classId]?.let { cachedResult ->
        result += cachedResult
        continue
      }

      val bindingContainerAnno =
        bindingContainerClass
          .annotationsIn(symbols.classIds.bindingContainerAnnotations)
          .firstOrNull() ?: continue
      result += bindingContainerClass

      // Add included binding containers to the queue
      for (includedClass in
        bindingContainerAnno.includedClasses().map { it.classType.rawTypeOrNull() }) {
        if (includedClass != null && includedClass.classIdOrFail !in localVisited) {
          queue += includedClass
        }
      }
    }

    return result
  }

  private data class ContributedIrBinding(
    val contributingType: IrClass,
    val typeKey: IrTypeKey,
    val rank: Long,
  )
}

internal class GeneratedGraphExtensionData(
  val typeKey: IrTypeKey,
)

internal var IrClass.generatedGraphExtensionData: GeneratedGraphExtensionData?
  by irAttribute(copyByDefault = false)
