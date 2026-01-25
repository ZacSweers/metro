// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
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
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.scopeClassOrNull
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.toIrVararg
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.symbols.Symbols
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
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.interpreter.hasAnnotation
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.properties
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
    // Extension graphs are static nested classes with an explicit parent graph parameter
    val hasParentGraph = parentGraph != null

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
          if (hasParentGraph) {
            addValueParameter(
              name = parentGraph.parentGraphParamName,
              type = parentGraph.defaultType,
            )
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
            if (hasParentGraph) {
              // First arg is always the parent graph instance
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

  private val IrClass.parentGraphParamName: Name
    get() {
      return if (name == Symbols.Names.Impl) {
        // Parent is a regular dependency graph, go up one level for clarity
        sourceGraphIfMetroGraph.name.suffixIfNot("Impl")
      } else {
        name
      }
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
          // For extension graphs, the parent graph is passed as an explicit constructor parameter
          // (not as a dispatch receiver since we're using static nested classes)
          if (parentGraph != null) {
            addValueParameter(
              parentGraph.parentGraphParamName.decapitalizeUS(),
              parentGraph.defaultType,
              Origins.ParentGraphParam,
            )
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

    graphImpl.validateOverrides()

    return CreatedGraphImpl(graphAnno, graphImpl, factoryImpl)
  }

  data class CreatedGraphImpl(
    val graphAnno: IrConstructorCall,
    val graphImpl: IrClass,
    val factoryImpl: IrClass?,
  )

  /**
   * Validates that fake overrides in this class don't have incompatible return types from different
   * supertypes. This catches cases like:
   * ```
   * interface A { val foo: Int }
   * interface B { val foo: String }
   * class C : A, B // Error: 'val foo: Int' clashes with 'val foo: String'
   * ```
   *
   * Adapted from the kotlinc impl in FirImplementationMismatchChecker.kt
   */
  // https://github.com/ZacSweers/metro/issues/904
  private fun IrClass.validateOverrides() {
    // Check all fake override properties
    for (property in properties) {
      if (!property.isFakeOverride) continue
      property.checkOverrideTypeCompatibility()
    }

    // Check all fake override functions
    for (function in functions) {
      if (!function.isFakeOverride) continue
      function.checkOverrideTypeCompatibility()
    }
  }

  /**
   * Checks that all overridden symbols of this declaration have compatible return types. Two types
   * are compatible if one is a subtype of the other.
   *
   * We check a single member's overridden symbol because this will manifest as incompatible
   * ancestor overridden symbols.
   */
  private fun <S : IrSymbol> IrOverridableDeclaration<S>.checkOverrideTypeCompatibility() {
    val overriddenSymbols = overriddenSymbols.toList()
    if (overriddenSymbols.size < 2) return

    // Get return types for each overridden declaration
    val overriddenWithTypes =
      overriddenSymbols.mapNotNull { symbol ->
        val owner = symbol.owner
        val returnType =
          when (owner) {
            is IrSimpleFunction -> owner.returnType
            is IrProperty -> owner.getter?.returnType ?: return@mapNotNull null
            else -> return@mapNotNull null
          }
        owner to returnType
      }

    if (overriddenWithTypes.size < 2) return

    // Check all pairs for compatibility - find if there's any type that is compatible with all
    // others
    val hasCompatibleType =
      overriddenWithTypes.any { (_, type1) ->
        overriddenWithTypes.all { (_, type2) ->
          type1.isSubtypeOf(type2, irTypeSystemContext) ||
            type2.isSubtypeOf(type1, irTypeSystemContext)
        }
      }

    if (hasCompatibleType) return

    // Find a concrete clash to report
    for (i in overriddenWithTypes.indices) {
      for (j in i + 1 until overriddenWithTypes.size) {
        val (decl1, type1) = overriddenWithTypes[i]
        val (decl2, type2) = overriddenWithTypes[j]

        if (
          !type1.isSubtypeOf(type2, irTypeSystemContext) &&
            !type2.isSubtypeOf(type1, irTypeSystemContext)
        ) {
          reportTypeClash(decl1, type1, decl2, type2)
          return // Report only the first clash
        }
      }
    }
  }

  private fun reportTypeClash(
    decl1: IrOverridableDeclaration<*>,
    type1: IrType,
    decl2: IrOverridableDeclaration<*>,
    type2: IrType,
  ) {
    val isProperty =
      decl1 is IrProperty ||
        (decl1 is IrSimpleFunction && decl1.correspondingPropertySymbol != null)
    val kind = if (isProperty) "val" else "fun"
    val typeMismatchKind = if (isProperty) "property" else "return"

    val name1 =
      when (decl1) {
        is IrProperty -> decl1.name.asString()
        is IrSimpleFunction ->
          decl1.correspondingPropertySymbol?.owner?.name?.asString() ?: decl1.name.asString()
      }

    val parent1 = decl1.parentAsClass.originIfContribution
    val parent2 = decl2.parentAsClass.originIfContribution
    val parent1Name = parent1.kotlinFqName
    val parent2Name = parent2.kotlinFqName

    val message = buildString {
      append("'$kind $name1: ${type1.render(short = false)}' defined in '$parent1Name' ")
      append(
        "clashes with '$kind $name1: ${type2.render(short = false)}' defined in '$parent2Name': "
      )
      append("$typeMismatchKind types are incompatible.")
    }

    metroContext.reportCompat(originDeclaration, MetroDiagnostics.METRO_ERROR, message)
  }

  private val IrClass.originIfContribution: IrClass
    get() {
      return if (hasAnnotation(Symbols.FqNames.MetroContribution)) {
        parentAsClass
      } else {
        this
      }
    }
}
