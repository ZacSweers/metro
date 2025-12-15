// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrBindingContainerResolver
import dev.zacsweers.metro.compiler.ir.IrContributionMerger
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.additionalScopes
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
import dev.zacsweers.metro.compiler.ir.kClassReference
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.scopeClassOrNull
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.toIrVararg
import dev.zacsweers.metro.compiler.ir.trackClassLookup
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
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name

internal data class SyntheticGraphParameter(
  val name: String,
  val type: IrType,
  val origin: IrDeclarationOrigin = Origins.Default,
)

internal class SyntheticGraphGenerator(
  metroContext: IrMetroContext,
  private val contributionMerger: IrContributionMerger,
  private val bindingContainerResolver: IrBindingContainerResolver,
  private val sourceAnnotation: IrConstructorCall?,
  private val parentGraph: IrClass?,
  private val originDeclaration: IrDeclaration,
  private val containerToAddTo: IrDeclarationContainer,
) : IrMetroContext by metroContext {

  val contributions =
    sourceAnnotation?.let { contributionMerger.computeContributions(it, originDeclaration) }

  /** Generates a factory implementation class that implements a factory interface. */
  private fun generateFactoryImpl(
    graphImpl: IrClass,
    graphCtor: IrConstructor,
    factoryInterface: IrClass,
    storedParams: List<SyntheticGraphParameter>,
  ): IrClass {
    val implIsInner = graphImpl.isInner

    // Create the factory implementation class
    val factoryImpl =
      metroContext.irFactory
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
          addFakeOverrides(metroContext.irTypeSystemContext)
        }

    // Add a constructor with the stored parameters
    val factoryConstructor =
      factoryImpl
        .addConstructor {
          visibility = DescriptorVisibilities.PUBLIC
          isPrimary = true
          returnType = factoryImpl.symbol.defaultType
        }
        .apply {
          if (implIsInner) {
            addValueParameter(name = "parentInstance", type = graphImpl.parentAsClass.defaultType)
          }
          storedParams.forEach { param -> addValueParameter(param.name, param.type) }
          body = generateDefaultConstructorBody()
        }

    // Assign constructor parameters to fields for later access
    val paramsToFields = assignConstructorParamsToFields(factoryConstructor, factoryImpl)

    // Get the SAM function that needs to be implemented
    val samFunction = factoryImpl.singleAbstractFunction()
    samFunction.finalizeFakeOverride(factoryImpl.thisReceiverOrFail)

    // Implement the SAM method body
    samFunction.body =
      metroContext.createIrBuilder(samFunction.symbol).run {
        irExprBodySafe(
          irCallConstructor(graphCtor.symbol, emptyList()).apply {
            val storedFields = paramsToFields.values.toMutableList()
            val samParams = samFunction.regularParameters

            var paramIndex = 0
            if (implIsInner) {
              // First arg is always the graph instance if it's inner
              arguments[paramIndex++] =
                irGetField(irGet(samFunction.dispatchReceiverParameter!!), storedFields.removeAt(0))
            }

            samParams.forEach { param -> arguments[paramIndex++] = irGet(param) }
            storedFields.forEach { field ->
              arguments[paramIndex++] =
                irGetField(irGet(samFunction.dispatchReceiverParameter!!), field)
            }
          }
        )
      }

    return factoryImpl
  }

  /** Builds a `@DependencyGraph` annotation for a generated graph class. */
  internal fun buildDependencyGraphAnnotation(targetClass: IrClass): IrConstructorCall {
    return buildAnnotation(
      targetClass.symbol,
      metroSymbols.metroDependencyGraphAnnotationConstructor,
    ) { annotation ->
      if (sourceAnnotation != null) {
        // scope
        sourceAnnotation.scopeClassOrNull()?.let {
          annotation.arguments[0] = kClassReference(it.symbol)
        }

        // additionalScopes
        sourceAnnotation.additionalScopes().copyToIrVararg()?.let { annotation.arguments[1] = it }

        // excludes
        sourceAnnotation.excludedClasses().copyToIrVararg()?.let { annotation.arguments[2] = it }

        // bindingContainers
        val allContainers = buildSet {
          val declaredContainers =
            sourceAnnotation
              .bindingContainerClasses(includeModulesArg = options.enableDaggerRuntimeInterop)
              .map { it.classType.rawType() }
          addAll(declaredContainers)
          contributions?.let { addAll(it.bindingContainers.values) }
        }
        allContainers.let(bindingContainerResolver::resolveTransitiveClosure).toIrVararg()?.let {
          annotation.arguments[3] = it
        }
      }
    }
  }

  fun generateImpl(
    name: Name,
    origin: IrDeclarationOrigin,
    supertype: IrType,
    creatorFunction: IrSimpleFunction?,
    storedParams: List<SyntheticGraphParameter> = emptyList(),
  ): CreatedGraphImpl {
    val graphImpl =
      irFactory.buildClass {
        this.name = name
        this.origin = origin
        kind = ClassKind.CLASS
        this.isInner = parentGraph != null
        visibility = DescriptorVisibilities.PRIVATE
      }

    val graphAnno = buildDependencyGraphAnnotation(targetClass = graphImpl)

    graphImpl.apply {
      createThisReceiverParameter()

      // Add a @DependencyGraph(...) annotation
      annotations += graphAnno

      superTypes += supertype

      // Add only non-binding-container contributions as supertypes
      if (contributions != null) {
        superTypes += contributions.supertypes
        contributions.supertypes.forEach { contribution ->
          contribution.rawTypeOrNull()?.let {
            trackClassLookup(parentGraph ?: originDeclaration, it)
          }
        }
      }

      // Must be added to the container before we generate a factory impl
      containerToAddTo.addChild(this)
    }

    val ctor =
      graphImpl
        .addConstructor {
          isPrimary = true
          this.origin = Origins.Default
          // This will be finalized in IrGraphGenerator
          isFakeOverride = true
        }
        .apply {
          // TODO generics?
          if (parentGraph != null) {
            setDispatchReceiver(parentGraph.thisReceiverOrFail.copyTo(this))
          }
          // Copy over any creator params
          creatorFunction?.let {
            for (param in it.regularParameters) {
              addValueParameter(param.name, param.type).apply { this.copyAnnotationsFrom(param) }
            }
          }

          // Then add stored parameters (for example, container params from dynamic graphs)
          for ((name, type, origin) in storedParams) {
            addValueParameter(name, type, origin = origin)
          }

          body = generateDefaultConstructorBody()
        }

    // If there's an extension, generate it into this impl
    val factoryImpl =
      creatorFunction?.let { factory ->
        // Don't need to do this if the parent implements the factory
        if (parentGraph?.implements(factory.parentAsClass.classIdOrFail) == true) return@let null
        generateFactoryImpl(
          graphImpl = graphImpl,
          graphCtor = ctor,
          factoryInterface = factory.parentAsClass,
          storedParams = storedParams,
        )
      }

    graphImpl.addFakeOverrides(irTypeSystemContext)

    return CreatedGraphImpl(graphAnno, graphImpl, factoryImpl)
  }

  data class CreatedGraphImpl(
    val graphAnno: IrConstructorCall,
    val graphImpl: IrClass,
    val factoryImpl: IrClass?,
  )
}
