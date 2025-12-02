// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.ir.IrBindingContainerResolver
import dev.zacsweers.metro.compiler.ir.IrContributionMerger
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.typeRemapperFor
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.name.ClassId

internal class IrGraphExtensionGenerator(
  context: IrMetroContext,
  private val contributionMerger: IrContributionMerger,
  private val bindingContainerResolver: IrBindingContainerResolver,
  private val parentGraph: IrClass,
) : IrMetroContext by context {

  private val classNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
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
              metroSymbols.classIds.graphExtensionFactoryAnnotations
            )
          }
          ?.owner ?: contributedAccessor.ir

      val parent = sourceSamFunction.parentClassOrNull ?: reportCompilerBug("No parent class found")
      val isFactorySAM =
        parent.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionFactoryAnnotations)
      if (isFactorySAM) {
        generateImplFromFactory(sourceSamFunction, parentTracer, typeKey)
      } else {
        val returnType = contributedAccessor.ir.returnType.rawType()
        val returnIsGraphExtensionFactory =
          returnType.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionFactoryAnnotations)
        val returnIsGraphExtension =
          returnType.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionAnnotations)
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
          reportCompilerBug("Not a graph extension: ${returnType.kotlinFqName}")
        }
      }
    }
  }

  private fun generateImplFromFactory(
    factoryFunction: IrSimpleFunction,
    parentTracer: Tracer,
    typeKey: IrTypeKey,
  ): IrClass {
    val sourceFactory = factoryFunction.parentAsClass
    val sourceGraph = sourceFactory.parentAsClass
    return parentTracer.traceNested("Generate graph extension ${sourceGraph.name}") {
      generateImpl(sourceGraph = sourceGraph, creatorFunction = factoryFunction, typeKey = typeKey)
    }
  }

  private fun generateImpl(
    sourceGraph: IrClass,
    creatorFunction: IrSimpleFunction?,
    typeKey: IrTypeKey,
  ): IrClass {
    val graphExtensionAnno =
      sourceGraph.annotationsIn(metroSymbols.classIds.graphExtensionAnnotations).firstOrNull()
    val extensionAnno =
      graphExtensionAnno
        ?: reportCompilerBug("Expected @GraphExtension on ${sourceGraph.kotlinFqName}")

    val syntheticGraphGenerator =
      SyntheticGraphGenerator(
        metroContext = metroContext,
        contributionMerger = contributionMerger,
        bindingContainerResolver = bindingContainerResolver,
        sourceAnnotation = extensionAnno,
        parentGraph = parentGraph,
        originDeclaration = parentGraph,
        containerToAddTo = parentGraph,
      )

    // Ensure a unique name
    val name =
      classNameAllocator
        .newName("${sourceGraph.name.asString().capitalizeUS()}${Symbols.StringNames.IMPL}")
        .asName()

    // Source is a `@GraphExtension`-annotated class, we want to generate a header impl class
    val (_, graphImpl, factoryImpl) =
      syntheticGraphGenerator.generateImpl(
        name = name,
        origin = Origins.GeneratedGraphExtension,
        supertype = sourceGraph.defaultType,
        creatorFunction = creatorFunction,
      )

    graphImpl.generatedGraphExtensionData =
      GeneratedGraphExtensionData(typeKey = typeKey, factoryImpl = factoryImpl)

    return graphImpl
  }
}

internal class GeneratedGraphExtensionData(val typeKey: IrTypeKey, val factoryImpl: IrClass? = null)

internal var IrClass.generatedGraphExtensionData: GeneratedGraphExtensionData? by
  irAttribute(copyByDefault = false)
