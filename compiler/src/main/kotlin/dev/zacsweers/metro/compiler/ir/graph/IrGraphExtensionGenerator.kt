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
import dev.zacsweers.metro.compiler.ir.additionalScopes
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.bindingContainerClasses
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.copyToIrVararg
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.excludedClasses
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.kClassReference
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.scopeClassOrNull
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.toIrVararg
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.typeRemapperFor
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
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

  private fun generateFactoryImplForCreator(
    graphImpl: IrClass,
    ctor: IrConstructor,
    creatorFunction: IrSimpleFunction,
    parentGraph: IrClass,
  ): IrClass {
    val factoryInterface = creatorFunction.parentAsClass

    // Create the factory implementation as a nested class
    val factoryImpl =
      pluginContext.irFactory
        .buildClass {
          name = "${factoryInterface.name}Impl".asName()
          kind = ClassKind.CLASS
          visibility = DescriptorVisibilities.PRIVATE
          origin = Origins.Default
        }
        .apply {
          this.superTypes = listOf(factoryInterface.defaultType)
          this.typeParameters = copyTypeParametersFrom(factoryInterface)
          this.createThisReceiverParameter()
          graphImpl.addChild(this)
          this.addFakeOverrides(irTypeSystemContext)
        }

    val constructor =
      factoryImpl
        .addConstructor {
          visibility = DescriptorVisibilities.PUBLIC
          isPrimary = true
          this.returnType = factoryImpl.defaultType
        }
        .apply {
          addValueParameter("parentInstance", parentGraph.defaultType)
          body = generateDefaultConstructorBody()
        }

    val paramsToFields = assignConstructorParamsToFields(constructor, factoryImpl)

    // Implement the SAM method
    val samFunction = factoryImpl.singleAbstractFunction()
    samFunction.finalizeFakeOverride(factoryImpl.thisReceiverOrFail)

    // Implement the factory SAM to create the extension
    samFunction.body =
      createIrBuilder(samFunction.symbol).run {
        irExprBodySafe(
          irCallConstructor(ctor.symbol, emptyList()).apply {
            // Firstc arg is always the graph instance
            arguments[0] =
              irGetField(
                irGet(samFunction.dispatchReceiverParameter!!),
                paramsToFields.values.first(),
              )
            for (i in 0 until samFunction.regularParameters.size) {
              arguments[i + 1] = irGet(samFunction.regularParameters[i])
            }
          }
        )
      }

    return factoryImpl
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

    val contributions = contributionMerger.computeContributions(extensionAnno)

    // Source is a `@GraphExtension`-annotated class, we want to generate a header impl class
    val graphImpl =
      pluginContext.irFactory
        .buildClass {
          // Ensure a unique name
          name =
            classNameAllocator
              .newName("${sourceGraph.name.asString().capitalizeUS()}${Symbols.StringNames.IMPL}")
              .asName()
          origin = Origins.GeneratedGraphExtension
          kind = ClassKind.CLASS
          isInner = true
        }
        .apply {
          createThisReceiverParameter()

          // Add a @DependencyGraph(...) annotation
          // TODO dedupe with dynamic graph gen
          annotations +=
            buildAnnotation(symbol, metroSymbols.metroDependencyGraphAnnotationConstructor) {
              annotation ->
              // scope
              extensionAnno.scopeClassOrNull()?.let {
                annotation.arguments[0] = kClassReference(it.symbol)
              }

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
                contributions?.let { addAll(it.bindingContainers.values) }
              }
              allContainers
                .let(bindingContainerResolver::resolveAllBindingContainersCached)
                .toIrVararg()
                ?.let { annotation.arguments[3] = it }
            }

          superTypes += sourceGraph.defaultType

          // Add only non-binding-container contributions as supertypes
          contributions?.let {
            superTypes += it.supertypes
            it.supertypes.forEach { contribution ->
              contribution.rawTypeOrNull()?.let { trackClassLookup(parentGraph, it) }
            }
          }

          val ctor =
            addConstructor {
                isPrimary = true
                origin = Origins.Default
                // This will be finalized in IrGraphGenerator
                isFakeOverride = true
              }
              .apply {
                // TODO generics?
                setDispatchReceiver(
                  parentGraph.thisReceiverOrFail.copyTo(this, type = parentGraph.defaultType)
                )
                // Copy over any creator params
                creatorFunction?.let {
                  for (param in it.regularParameters) {
                    addValueParameter(param.name, param.type).apply {
                      this.copyAnnotationsFrom(param)
                    }
                  }
                }

                body = this.generateDefaultConstructorBody()
              }

          // Must be added to the parent before we generate a factory impl
          parentGraph.addChild(this)

          // If there's an extension, generate it into this impl
          val factoryImpl =
            creatorFunction?.let { factory ->
              // Don't need to do this if the parent implements the factory
              if (parentGraph.implements(factory.parentAsClass.classIdOrFail)) return@let null
              generateFactoryImplForCreator(
                graphImpl = this,
                ctor = ctor,
                creatorFunction = factory,
                parentGraph = this@IrGraphExtensionGenerator.parentGraph,
              )
            }

          generatedGraphExtensionData =
            GeneratedGraphExtensionData(typeKey = typeKey, factoryImpl = factoryImpl)
        }

    graphImpl.addFakeOverrides(irTypeSystemContext)

    return graphImpl
  }
}

internal class GeneratedGraphExtensionData(val typeKey: IrTypeKey, val factoryImpl: IrClass? = null)

internal var IrClass.generatedGraphExtensionData: GeneratedGraphExtensionData? by
  irAttribute(copyByDefault = false)
