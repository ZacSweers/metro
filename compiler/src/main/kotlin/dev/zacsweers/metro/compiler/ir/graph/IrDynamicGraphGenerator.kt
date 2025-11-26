// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrBindingContainerResolver
import dev.zacsweers.metro.compiler.ir.IrContributionMerger
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.additionalScopes
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.bindingContainerClasses
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.copyToIrVararg
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.excludedClasses
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.kClassReference
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.scopeClassOrNull
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.toIrVararg
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.TransformerContextAccess
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.md5base64
import dev.zacsweers.metro.compiler.reportCompilerBug
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
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
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

    return generatedClassesCache.getOrPut(cacheKey) {
      generateDynamicGraph(
        targetType = targetType,
        containerTypeKeys = containerTypeKeys,
        isFactory = isFactory,
        context = context,
      )
    }
  }

  // TODO consolidate logic with extension graph gen
  private fun generateDynamicGraph(
    targetType: IrType,
    containerTypeKeys: Set<IrTypeKey>,
    isFactory: Boolean,
    context: TransformerContextAccess,
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

    val contributions = contributionMerger.computeContributions(targetGraphAnno)

    lateinit var newGraphAnno: IrConstructorCall
    val graphImpl =
      pluginContext.irFactory
        .buildClass {
          name = graphName
          origin = Origins.GeneratedDynamicGraph
          kind = ClassKind.CLASS
          visibility = DescriptorVisibilities.PRIVATE
        }
        .apply {
          createThisReceiverParameter()

          // Extend the target type (graph interface or factory interface)
          superTypes += factorySamFunction?.returnType ?: targetType

          // Add discovered contribution supertypes
          contributions?.let { superTypes += it.supertypes }

          // Copy @DependencyGraph annotation from target
          // TODO dedupe with graph extension gen
          annotations +=
            buildAnnotation(symbol, metroSymbols.metroDependencyGraphAnnotationConstructor) {
                annotation ->
                // scope
                targetGraphAnno.scopeClassOrNull()?.let {
                  annotation.arguments[0] = kClassReference(it.symbol)
                }

                // additionalScopes
                targetGraphAnno.additionalScopes().copyToIrVararg()?.let {
                  annotation.arguments[1] = it
                }

                // excludes
                targetGraphAnno.excludedClasses().copyToIrVararg()?.let {
                  annotation.arguments[2] = it
                }

                // bindingContainers
                // don't include dynamic containers
                val allContainers = buildSet {
                  val declaredContainers =
                    targetGraphAnno
                      .bindingContainerClasses(
                        includeModulesArg = options.enableDaggerRuntimeInterop
                      )
                      .map { it.classType.rawType() }
                  addAll(declaredContainers)
                  contributions?.let { addAll(it.bindingContainers.values) }
                }
                allContainers
                  .let(bindingContainerResolver::resolveAllBindingContainersCached)
                  .toIrVararg()
                  ?.let { annotation.arguments[3] = it }
              }
              .also { newGraphAnno = it }

          val ctor =
            addConstructor { isPrimary = true }
              .apply ctor@{
                // If this is a factory, add the factory SAM function's parameters FIRST
                factorySamFunction?.let { samFunction ->
                  for (param in samFunction.regularParameters) {
                    addValueParameter(param.name, param.type).apply {
                      this.copyAnnotationsFrom(param)
                    }
                  }
                }

                // Then add container parameters
                containerClasses.forEachIndexed { index, containerClass ->
                  addValueParameter(
                    "container$index",
                    containerClass.symbol.defaultType,
                    origin = Origins.DynamicContainerParam,
                  )
                }

                body = generateDefaultConstructorBody()
              }

          // Store the overriding containers for later use
          overridingBindingContainers = containerTypeKeys

          // Add the generated class as a nested class in the call site's parent class,
          // or as a file-level class if no parent exists
          val containerToAddTo: IrDeclarationContainer =
            context.currentClassAccess?.irElement as? IrClass ?: context.currentFileAccess
          containerToAddTo.addChild(this)

          addFakeOverrides(irTypeSystemContext)

          // If this is a factory, generate a factory impl using shared logic
          val factoryImpl =
            factorySamFunction?.let {
              generateFactoryImpl(
                graphImpl = this,
                graphCtor = ctor,
                factoryInterface = rawType,
                containerClasses = containerClasses,
              )
            }

          // Store factory impl for later reference if needed
          if (factoryImpl != null) {
            generatedDynamicGraphData = GeneratedDynamicGraphData(factoryImpl = factoryImpl)
          }
        }

    dependencyGraphTransformer.processDependencyGraph(graphImpl, newGraphAnno, graphImpl, null)

    return graphImpl
  }

  // TODO reuse logic with assisted factory impl gen
  private fun generateFactoryImpl(
    graphImpl: IrClass,
    graphCtor: IrConstructor,
    factoryInterface: IrClass,
    containerClasses: List<IrClass>,
  ): IrClass {
    // Create the factory implementation class
    val factoryImpl =
      pluginContext.irFactory
        .buildClass {
          name = "${factoryInterface.name}Impl".asName()
          kind = ClassKind.CLASS
          visibility = DescriptorVisibilities.PRIVATE
          origin = Origins.Default
        }
        .apply {
          superTypes = listOf(factoryInterface.symbol.defaultType)
          typeParameters = copyTypeParametersFrom(factoryInterface)
          createThisReceiverParameter()
          graphImpl.addChild(this)
          addFakeOverrides(irTypeSystemContext)
        }

    // Add a constructor that stores the container parameters as fields
    val factoryConstructor =
      factoryImpl
        .addConstructor { isPrimary = true }
        .apply {
          containerClasses.forEachIndexed { index, containerClass ->
            addValueParameter("container$index", containerClass.symbol.defaultType)
          }
          body = generateDefaultConstructorBody()
        }

    // Assign constructor parameters to fields for later access
    val paramsToFields = assignConstructorParamsToFields(factoryConstructor, factoryImpl)

    // Get the SAM function that needs to be implemented
    val samFunction = factoryImpl.singleAbstractFunction()
    samFunction.finalizeFakeOverride(factoryImpl.thisReceiverOrFail)

    // Implement the SAM method body
    // Constructor parameters are: SAM params first, then container instances
    samFunction.body =
      createIrBuilder(samFunction.symbol).run {
        irExprBodySafe(
          irCallConstructor(graphCtor.symbol, emptyList()).apply {
            // First, pass SAM parameters
            samFunction.regularParameters.forEachIndexed { index, param ->
              arguments[index] = irGet(param)
            }
            // Then, pass container instances from the factory's fields
            val samParamCount = samFunction.regularParameters.size
            paramsToFields.values.toList().forEachIndexed { index, field ->
              arguments[samParamCount + index] =
                irGetField(irGet(samFunction.dispatchReceiverParameter!!), field)
            }
          }
        )
      }

    return factoryImpl
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
