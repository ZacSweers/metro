// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.hashSuffix
import dev.zacsweers.metro.compiler.ir.GraphToProcess
import dev.zacsweers.metro.compiler.ir.IrBindingContainerResolver
import dev.zacsweers.metro.compiler.ir.IrContributionMerger
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrScope
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.SyntheticGraphs
import dev.zacsweers.metro.compiler.ir.allScopes
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.transformers.TransformerContextAccess
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.TraceScope
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@Inject
@SingleIn(IrScope::class)
internal class IrDynamicGraphGenerator(
  metroContext: IrMetroContext,
  private val bindingContainerResolver: IrBindingContainerResolver,
  private val contributionMerger: IrContributionMerger,
  @SyntheticGraphs syntheticGraphs: MutableList<GraphToProcess>,
) : IrMetroContext by metroContext {

  private val onGraphGenerated: (graphImpl: IrClass, graphAnno: IrConstructorCall) -> Unit =
    { impl, anno ->
      syntheticGraphs += GraphToProcess(impl, anno, impl, anno.allScopes())
    }
  private val generatedClassesCache = mutableMapOf<CacheKey, IrClass>()
  private val classNameAllocators = mutableMapOf<IrDeclarationContainer, NameAllocator>()
  // This generator belongs to one IR module. Its package names are seeded on first use.
  private val packageNameAllocators = mutableMapOf<FqName, NameAllocator>()

  // callerFile keys the cache so call sites in different files/packages don't share an impl and end
  // up referencing another file's package-private nested class.
  // https://github.com/ZacSweers/metro/issues/2324
  private data class CacheKey(
    val targetTypeKey: IrTypeKey,
    val containerKeys: Set<IrTypeKey>,
    val isFactory: Boolean,
    val callerFile: IrFile,
  )

  context(traceScope: TraceScope)
  fun getOrBuildDynamicGraph(
    targetType: IrType,
    containerTypeKeys: List<IrTypeKey>,
    isFactory: Boolean,
    context: TransformerContextAccess,
    containingFunction: IrSimpleFunction,
    sourceExpression: IrCall,
  ): IrClass {
    val cacheKey =
      CacheKey(
        targetTypeKey = IrTypeKey(targetType),
        containerKeys = containerTypeKeys.toSet(),
        isFactory = isFactory,
        callerFile = context.currentFileAccess,
      )

    return generatedClassesCache
      .getOrPut(cacheKey) {
        generateDynamicGraph(
          targetType = targetType,
          containerTypeKeys = containerTypeKeys,
          cacheKey = cacheKey,
          isFactory = isFactory,
          context = context,
          containingFunction = containingFunction,
          sourceExpression = sourceExpression,
        )
      }
      .also {
        // link for IC
        trackClassLookup(containingFunction, it)
      }
  }

  context(traceScope: TraceScope)
  private fun generateDynamicGraph(
    targetType: IrType,
    containerTypeKeys: List<IrTypeKey>,
    cacheKey: CacheKey,
    isFactory: Boolean,
    context: TransformerContextAccess,
    containingFunction: IrSimpleFunction,
    sourceExpression: IrCall,
  ): IrClass {
    val rawType = targetType.rawType()
    // Get factory SAM function if this is a factory
    val factorySamFunction =
      if (isFactory) {
        rawType.singleAbstractFunction()
      } else {
        null
      }

    val targetClass = factorySamFunction?.let { factorySamFunction.returnType.rawType() } ?: rawType

    // Add the generated class as a nested class in the call site's parent class,
    // or as a file-level class if no parent exists
    val containerToAddTo: IrDeclarationContainer =
      context.currentClassAccess?.irElement as? IrClass ?: context.currentFileAccess

    val graphName = allocateGraphName(targetClass.classIdOrFail, cacheKey, containerToAddTo)

    // Get the target graph's @DependencyGraph annotation
    val targetGraphAnno =
      targetClass.annotationsIn(metroSymbols.classIds.dependencyGraphAnnotations).firstOrNull()
        ?: reportCompilerBug("Expected @DependencyGraph on ${targetClass.kotlinFqName}")

    val syntheticGraphGenerator =
      SyntheticGraphGenerator(
        metroContext = metroContext,
        contributionMerger = contributionMerger,
        bindingContainerResolver = bindingContainerResolver,
        sourceAnnotation = targetGraphAnno,
        parentGraph = null,
        originDeclaration = containingFunction,
        containerToAddTo = containerToAddTo,
        traceScope = traceScope,
      )

    // Extend the target type (graph interface or factory interface)
    val supertype = factorySamFunction?.returnType ?: targetType

    val storedParams = containerTypeKeys.mapIndexed { index, containerTypeKey ->
      SyntheticGraphParameter(
        name = "container$index",
        type = containerTypeKey.type,
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
    graphImpl.overridingBindingContainers = cacheKey.containerKeys

    // Store data for later reference if needed
    graphImpl.generatedDynamicGraphData =
      GeneratedDynamicGraphData(
        containerTypeKeys = containerTypeKeys,
        factoryImpl = factoryImpl,
        sourceExpression = sourceExpression,
      )

    // Process the new graph
    onGraphGenerated(graphImpl, newGraphAnno)

    return graphImpl
  }

  /** Uses complete types for stable names and handles collisions within each class or package. */
  private fun allocateGraphName(
    targetGraphClassId: ClassId,
    cacheKey: CacheKey,
    containerToAddTo: IrDeclarationContainer,
  ): Name {
    // Set-based cache identity is order-independent. Generic arguments and the target factory
    // type participate in the name hash as well.
    val hash =
      buildList<Any> {
          add(cacheKey.targetTypeKey.render(short = false))
          add(cacheKey.isFactory)
          addAll(cacheKey.containerKeys.map { it.render(short = false) }.sorted())
          // Include the file to keep sibling impl names stable when their traversal order changes.
          if (containerToAddTo is IrFile) {
            add(containerToAddTo.fileEntry.name)
          }
        }
        .hashSuffix

    val targetSimpleName = targetGraphClassId.shortClassName.asString()
    val allocator =
      if (containerToAddTo is IrFile) {
        // Sibling files share a package namespace. One module pass reserves their class names;
        // the shared allocator also handles file-name hash collisions such as Aa.kt and BB.kt.
        if (packageNameAllocators.isEmpty()) {
          for (file in containerToAddTo.module.files) {
            val packageAllocator =
              packageNameAllocators.getOrPut(file.packageFqName) {
                NameAllocator(mode = NameAllocator.Mode.COUNT)
              }
            for (declaration in file.declarations.filterIsInstance<IrClass>()) {
              packageAllocator.reserveName(declaration.name.asString())
            }
          }
        }
        packageNameAllocators.getValue(containerToAddTo.packageFqName)
      } else {
        classNameAllocators.getOrPut(containerToAddTo) {
          NameAllocator(mode = NameAllocator.Mode.COUNT).apply {
            for (declaration in containerToAddTo.declarations.filterIsInstance<IrClass>()) {
              reserveName(declaration.name.asString())
            }
          }
        }
      }
    return allocator.newName("Dynamic${targetSimpleName}Impl_${hash}").asName()
  }
}

/** Keeps the constructor layout so calls sharing an implementation can reorder their arguments. */
internal class GeneratedDynamicGraphData(
  val containerTypeKeys: List<IrTypeKey>,
  val factoryImpl: IrClass? = null,
  val sourceExpression: IrCall? = null,
)

// Extension property to store generated dynamic graph data
internal var IrClass.generatedDynamicGraphData: GeneratedDynamicGraphData? by
  irAttribute(copyByDefault = false)

// Extension property to store overriding binding containers
internal var IrClass.overridingBindingContainers: Set<IrTypeKey>? by
  irAttribute(copyByDefault = false)
