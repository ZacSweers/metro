// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrBindingContainerResolver
import dev.zacsweers.metro.compiler.ir.IrContributionMerger
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.TransformerContextAccess
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.md5base64
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class IrDynamicGraphGenerator(
  private val dependencyGraphTransformer: DependencyGraphTransformer,
  private val bindingContainerResolver: IrBindingContainerResolver,
  private val contributionMerger: IrContributionMerger,
) : IrMetroContext by dependencyGraphTransformer {

  private val generatedClassesCache = mutableMapOf<CacheKey, IrClass>()

  private data class CacheKey(val targetGraphClassId: ClassId, val containerKeys: Set<IrTypeKey>)

  fun getOrBuildDynamicGraph(
    targetType: IrType,
    containerTypes: Set<IrType>,
    isFactory: Boolean,
    context: TransformerContextAccess,
    containingFunction: IrSimpleFunction,
  ): IrClass {
    val targetClass = targetType.rawType()

    val containerTypeKeys =
      containerTypes.mapToSet {
        it
          .asContextualTypeKey(
            qualifierAnnotation = null,
            hasDefault = false,
            patchMutableCollections = false,
            declaration = null,
          )
          .typeKey
      }

    val cacheKey =
      CacheKey(targetGraphClassId = targetClass.classIdOrFail, containerKeys = containerTypeKeys)

    return generatedClassesCache
      .getOrPut(cacheKey) {
        generateDynamicGraph(
          targetType = targetType,
          containerTypeKeys = containerTypeKeys,
          isFactory = isFactory,
          context = context,
          containingFunction,
        )
      }
      .also {
        // link for IC
        trackClassLookup(containingFunction, it)
      }
  }

  private fun generateDynamicGraph(
    targetType: IrType,
    containerTypeKeys: Set<IrTypeKey>,
    isFactory: Boolean,
    context: TransformerContextAccess,
    containingFunction: IrSimpleFunction,
  ): IrClass {
    val rawType = targetType.rawType()
    // Get factory SAM function if this is a factory
    val factorySamFunction = if (isFactory) rawType.singleAbstractFunction() else null

    val targetClass = factorySamFunction?.let { factorySamFunction.returnType.rawType() } ?: rawType
    val containerClasses = containerTypeKeys.map { it.type.rawType() }
    val containerClassIds = containerClasses.map { it.classIdOrFail }.toSet()
    val graphName = computeStableName(targetClass.classIdOrFail, containerClassIds)

    // Get the target graph's @DependencyGraph annotation
    val targetGraphAnno =
      targetClass.annotationsIn(metroSymbols.classIds.dependencyGraphAnnotations).firstOrNull()
        ?: reportCompilerBug("Expected @DependencyGraph on ${targetClass.kotlinFqName}")

    // Add the generated class as a nested class in the call site's parent class,
    // or as a file-level class if no parent exists
    val containerToAddTo: IrDeclarationContainer =
      context.currentClassAccess?.irElement as? IrClass ?: context.currentFileAccess

    val syntheticGraphGenerator =
      SyntheticGraphGenerator(
        metroContext = metroContext,
        contributionMerger = contributionMerger,
        bindingContainerResolver = bindingContainerResolver,
        sourceAnnotation = targetGraphAnno,
        parentGraph = null,
        originDeclaration = containingFunction,
        containerToAddTo = containerToAddTo,
      )

    // Extend the target type (graph interface or factory interface)
    val supertype = factorySamFunction?.returnType ?: targetType

    val storedParams =
      containerClasses.mapIndexed { index, containerClass ->
        SyntheticGraphParameter(
          name = "container$index",
          type = containerClass.symbol.defaultType,
          origin = Origins.DynamicContainerParam,
        )
      }

    val (newGraphAnno, graphImpl, factoryImpl) =
      syntheticGraphGenerator.generateImpl(
        name = graphName,
        origin = Origins.GeneratedDynamicGraph,
        supertype = supertype,
        creatorFunction = factorySamFunction,
        storedParams = storedParams,
      )

    // Store the overriding containers for later use
    graphImpl.overridingBindingContainers = containerTypeKeys

    // Store factory impl for later reference if needed
    if (factoryImpl != null) {
      graphImpl.generatedDynamicGraphData = GeneratedDynamicGraphData(factoryImpl = factoryImpl)
    }

    // Process the new graph
    dependencyGraphTransformer.processDependencyGraph(graphImpl, newGraphAnno, graphImpl, null)

    return graphImpl
  }

  private fun computeStableName(
    targetGraphClassId: ClassId,
    containerClassIds: Set<ClassId>,
  ): Name {
    // Sort container IDs for order-independence
    val sortedIds = containerClassIds.sortedBy { it.toString() }

    // Compute stable hash from target graph and sorted containers
    val hash =
      md5base64(
        buildList {
          add(targetGraphClassId)
          addAll(sortedIds)
        }
      )

    val targetSimpleName = targetGraphClassId.shortClassName.asString()
    return "Dynamic${targetSimpleName}Impl_${hash}".asName()
  }
}

// Data class to store generated dynamic graph metadata
internal class GeneratedDynamicGraphData(val factoryImpl: IrClass? = null)

// Extension property to store generated dynamic graph data
internal var IrClass.generatedDynamicGraphData: GeneratedDynamicGraphData? by
  irAttribute(copyByDefault = false)

// Extension property to store overriding binding containers
internal var IrClass.overridingBindingContainers: Set<IrTypeKey>? by
  irAttribute(copyByDefault = false)
