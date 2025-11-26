// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.BitField
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.BindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrBindingContainerCallable
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrContributionMerger
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.MultibindsCallable
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.allCallableMembers
import dev.zacsweers.metro.compiler.ir.allSupertypesSequence
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.bindingContainerClasses
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.excludedClasses
import dev.zacsweers.metro.compiler.ir.isAccessorCandidate
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isBindingContainer
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.isInheritedFromAny
import dev.zacsweers.metro.compiler.ir.linkDeclarationsInCompilation
import dev.zacsweers.metro.compiler.ir.locationOrNull
import dev.zacsweers.metro.compiler.ir.metroAnnotationsOf
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.metroGraphOrNull
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInMembersInjector
import dev.zacsweers.metro.compiler.ir.qualifierAnnotation
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.ir.renderForDiagnostic
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.scopeAnnotations
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.isInvisibleGeneratedGraph
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.memoized
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import java.util.EnumSet
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.ClassId

internal class DependencyGraphNodeCache(
  metroContext: IrMetroContext,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val contributionMerger: IrContributionMerger,
) : IrMetroContext by metroContext {

  // Keyed by the source declaration
  private val dependencyGraphNodesByClass = mutableMapOf<ClassId, DependencyGraphNode>()

  operator fun get(classId: ClassId) = dependencyGraphNodesByClass[classId]

  fun requirePreviouslyComputed(classId: ClassId) = dependencyGraphNodesByClass.getValue(classId)

  fun getOrComputeDependencyGraphNode(
    graphDeclaration: IrClass,
    bindingStack: IrBindingStack,
    parentTracer: Tracer,
    metroGraph: IrClass? = null,
    dependencyGraphAnno: IrConstructorCall? = null,
  ): DependencyGraphNode {
    if (!graphDeclaration.origin.isInvisibleGeneratedGraph) {
      val sourceGraph = graphDeclaration.sourceGraphIfMetroGraph
      if (sourceGraph != graphDeclaration) {
        return getOrComputeDependencyGraphNode(
          sourceGraph,
          bindingStack,
          parentTracer,
          metroGraph,
          dependencyGraphAnno,
        )
      }
    }

    val graphClassId = graphDeclaration.classIdOrFail

    return dependencyGraphNodesByClass.getOrPut(graphClassId) {
      parentTracer.traceNested("Build DependencyGraphNode") { tracer ->
        Builder(this, graphDeclaration, bindingStack, tracer, metroGraph, dependencyGraphAnno)
          .build()
      }
    }
  }

  private class Builder(
    private val nodeCache: DependencyGraphNodeCache,
    private val graphDeclaration: IrClass,
    private val bindingStack: IrBindingStack,
    private val parentTracer: Tracer,
    metroGraph: IrClass? = null,
    cachedDependencyGraphAnno: IrConstructorCall? = null,
  ) : IrMetroContext by nodeCache {
    private val metroGraph = metroGraph ?: graphDeclaration.metroGraphOrNull
    private val bindingContainerTransformer: BindingContainerTransformer =
      nodeCache.bindingContainerTransformer
    private val accessors = mutableListOf<GraphAccessor>()
    private val bindsFunctions = mutableListOf<Pair<MetroSimpleFunction, IrContextualTypeKey>>()
    private val bindsCallables = mutableMapOf<IrTypeKey, BindsCallable>()
    private val multibindsCallables = mutableSetOf<MultibindsCallable>()
    private val optionalKeys = mutableMapOf<IrTypeKey, MutableSet<BindsOptionalOfCallable>>()
    private val scopes = mutableSetOf<IrAnnotation>()
    private val providerFactories = mutableMapOf<IrTypeKey, ProviderFactory>()
    private val extendedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
    private val graphExtensions = mutableMapOf<IrTypeKey, MutableList<GraphExtensionAccessor>>()
    private val injectors = mutableListOf<InjectorFunction>()
    private val includedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
    private val graphTypeKey = IrTypeKey(graphDeclaration.typeWith())
    private val sourceGraphTypeKey = IrTypeKey(graphDeclaration.sourceGraphIfMetroGraph.typeWith())
    private val graphContextKey = IrContextualTypeKey.create(graphTypeKey)
    private val bindingContainers = mutableSetOf<BindingContainer>()
    private val managedBindingContainers = mutableSetOf<IrClass>()
    private val dynamicBindingContainers = mutableSetOf<IrClass>()
    private val dynamicTypeKeys = mutableMapOf<IrTypeKey, IrBindingContainerCallable?>()

    private val dependencyGraphAnno =
      cachedDependencyGraphAnno
        ?: graphDeclaration.annotationsIn(metroSymbols.dependencyGraphAnnotations).singleOrNull()
    private val aggregationScopes = mutableSetOf<ClassId>()
    private val isGraph = dependencyGraphAnno != null
    private val supertypes =
      (metroGraph ?: graphDeclaration).allSupertypesSequence(excludeSelf = false).memoized()

    private var hasGraphExtensions = false

    private fun computeDeclaredScopes(): Set<IrAnnotation> {
      return buildSet {
        val implicitScope =
          dependencyGraphAnno?.getValueArgument(Symbols.Names.scope)?.let { scopeArg ->
            scopeArg.expectAsOrNull<IrClassReference>()?.classType?.rawTypeOrNull()?.let {
              aggregationScopes += it.classIdOrFail
            }
            // Create a synthetic SingleIn(scope)
            pluginContext.createIrBuilder(graphDeclaration.symbol).run {
              irCall(metroSymbols.metroSingleInConstructor).apply { arguments[0] = scopeArg }
            }
          }

        if (implicitScope != null) {
          add(IrAnnotation(implicitScope))
          dependencyGraphAnno
            .getValueArgument(Symbols.Names.additionalScopes)
            ?.expectAs<IrVararg>()
            ?.elements
            ?.forEach { scopeArg ->
              scopeArg.expectAsOrNull<IrClassReference>()?.classType?.rawTypeOrNull()?.let {
                aggregationScopes += it.classIdOrFail
              }
              val scopeClassExpression = scopeArg.expectAs<IrExpression>()
              val newAnno =
                pluginContext.createIrBuilder(graphDeclaration.symbol).run {
                  irCall(metroSymbols.metroSingleInConstructor).apply {
                    arguments[0] = scopeClassExpression
                  }
                }
              add(IrAnnotation(newAnno))
            }
        }
        addAll(graphDeclaration.scopeAnnotations())
      }
    }

    private fun buildCreator(): DependencyGraphNode.Creator? {
      var bindingContainerFields = BitField()
      fun populateBindingContainerFields(parameters: Parameters) {
        for ((i, parameter) in parameters.regularParameters.withIndex()) {
          if (parameter.isIncludes) {
            val parameterClass = parameter.typeKey.type.classOrNull?.owner ?: continue

            linkDeclarationsInCompilation(graphDeclaration, parameterClass)

            // Check if the parameter is a binding container
            if (parameterClass.isBindingContainer()) {
              bindingContainerFields = bindingContainerFields.withSet(i)
            }
          }
        }
      }

      val creator =
        if (graphDeclaration.origin.isInvisibleGeneratedGraph) {
          val ctor = graphDeclaration.primaryConstructor!!
          val ctorParams = ctor.parameters()
          populateBindingContainerFields(ctorParams)
          DependencyGraphNode.Creator.Constructor(
            graphDeclaration,
            graphDeclaration.primaryConstructor!!,
            ctorParams,
            bindingContainerFields,
          )
        } else {
          // TODO since we already check this in FIR can we leave a more specific breadcrumb
          //  somewhere
          graphDeclaration.nestedClasses
            .singleOrNull { klass ->
              klass.isAnnotatedWithAny(metroSymbols.dependencyGraphFactoryAnnotations)
            }
            ?.let { factory ->
              // Validated in FIR so we can assume we'll find just one here
              val createFunction = factory.singleAbstractFunction()
              val parameters = createFunction.parameters()
              populateBindingContainerFields(parameters)
              DependencyGraphNode.Creator.Factory(
                factory,
                createFunction,
                parameters,
                bindingContainerFields,
              )
            }
        }

      creator?.let { nonNullCreator ->
        nonNullCreator.parameters.regularParameters.forEachIndexed { i, parameter ->
          if (parameter.isBindsInstance) return@forEachIndexed

          // It's an `@Includes` parameter
          val klass = parameter.typeKey.type.rawType()
          val sourceGraph = klass.sourceGraphIfMetroGraph

          checkGraphSelfCycle(graphDeclaration, graphTypeKey, bindingStack)

          // Add any included graph provider factories IFF it's a binding container
          val isDynamicContainer = parameter.ir?.origin == Origins.DynamicContainerParam
          if (isDynamicContainer) {
            dynamicBindingContainers += klass
            // Parameter's dynamism will be checked by its origin
            dynamicTypeKeys[parameter.typeKey] = null
          }
          val isRegularContainer = nonNullCreator.bindingContainersParameterIndices.isSet(i)
          val isContainer = isDynamicContainer || isRegularContainer
          if (isContainer) {
            // Include the container itself and all its transitively included containers
            val allContainers =
              bindingContainerTransformer.resolveAllBindingContainersCached(setOf(sourceGraph))
            bindingContainers += allContainers
            // Track which transitively included containers be managed
            for (container in allContainers) {
              if (container.ir == klass) {
                // Don't mark the parameter class itself as managed since we're taking it as an
                // input
                continue
              } else if (!isDynamicContainer && container.canBeManaged) {
                managedBindingContainers += container.ir
              }
            }
            return@forEachIndexed
          }

          // It's a graph-like
          val node =
            bindingStack.withEntry(
              IrBindingStack.Entry.injectedAt(graphContextKey, nonNullCreator.function)
            ) {
              val nodeKey =
                if (klass.origin.isInvisibleGeneratedGraph) {
                  klass
                } else {
                  sourceGraph
                }
              nodeCache.getOrComputeDependencyGraphNode(nodeKey, bindingStack, parentTracer)
            }

          // Still tie to the parameter key because that's what gets the instance binding
          if (parameter.isIncludes) {
            includedGraphNodes[parameter.typeKey] = node
          } else if (parameter.ir?.origin == Origins.DynamicContainerParam) {
            // Do nothing, it'll be checked separately in IrGraphGen
          } else {
            reportCompilerBug("Unexpected parameter type for graph: $parameter")
          }
        }
      }

      return creator
    }

    private fun checkGraphSelfCycle(
      graphDeclaration: IrClass,
      graphTypeKey: IrTypeKey,
      bindingStack: IrBindingStack,
    ) {
      if (bindingStack.entryFor(graphTypeKey) != null) {
        // TODO dagger doesn't appear to error for this case to model off of
        val message = buildString {
          if (bindingStack.entries.size == 1) {
            // If there's just one entry, specify that it's a self-referencing cycle for clarity
            appendLine("Graph dependency cycle detected! The below graph depends on itself.")
          } else {
            appendLine("Graph dependency cycle detected!")
          }
          appendBindingStack(bindingStack, short = false)
        }
        metroContext.reportCompat(
          graphDeclaration,
          MetroDiagnostics.GRAPH_DEPENDENCY_CYCLE,
          message,
        )
        exitProcessing()
      }
    }

    private var hasDuplicateBindingErrors = false

    private fun reportDuplicateProviderFactory(
      typeKey: IrTypeKey,
      existing: ProviderFactory,
      duplicate: ProviderFactory,
    ) {
      hasDuplicateBindingErrors = true

      fun StringBuilder.appendFactory(factory: ProviderFactory) {
        append("  ")
        appendLine(
          factory.function.locationOrNull()?.render(short = true) ?: factory.callableId.toString()
        )
        append("    ")
        renderForDiagnostic(
          declaration = factory.function,
          short = false,
          typeKey = factory.rawTypeKey,
          annotations = factory.annotations,
          parameters = factory.parameters,
          isProperty = factory.isPropertyAccessor,
          underlineTypeKey = true,
        )
      }

      val message = buildString {
        appendLine(
          "[Metro/DuplicateBinding] Multiple bindings found for ${typeKey.render(short = false, includeQualifier = true)}"
        )
        appendLine()
        // Render each location with its signature (indented with 4 spaces)
        appendFactory(existing)
        appendLine()
        appendFactory(duplicate)
        appendBindingStack(bindingStack, short = false)
      }
      reportCompat(graphDeclaration.sourceGraphIfMetroGraph, MetroDiagnostics.METRO_ERROR, message)
    }

    private fun reportDuplicateBindsCallable(
      typeKey: IrTypeKey,
      existing: BindsCallable,
      duplicate: BindsCallable,
    ) {
      hasDuplicateBindingErrors = true
      val existingDiagnostic =
        existing.renderLocationDiagnostic(short = false, existing.function.parameters())
      val duplicateDiagnostic =
        duplicate.renderLocationDiagnostic(short = false, duplicate.function.parameters())
      val message = buildString {
        appendLine(
          "[Metro/DuplicateBinding] Multiple bindings found for ${typeKey.render(short = false, includeQualifier = true)}"
        )
        appendLine()
        append("  ")
        appendLine(existingDiagnostic.location)
        // Indent each line of the description (content line + underline line)
        existingDiagnostic.description?.lines()?.forEach { line ->
          append("    ")
          appendLine(line)
        }
        append("  ")
        appendLine(duplicateDiagnostic.location)
        // Indent each line of the description (content line + underline line)
        duplicateDiagnostic.description?.lines()?.forEachIndexed { index, line ->
          append("    ")
          if (index < (duplicateDiagnostic.description.lines().size - 1)) {
            appendLine(line)
          } else {
            append(line)
          }
        }
        appendBindingStack(bindingStack, short = false)
      }
      reportCompat(graphDeclaration.sourceGraphIfMetroGraph, MetroDiagnostics.METRO_ERROR, message)
    }

    private fun reportQualifierMismatch(
      declaration: IrOverridableDeclaration<*>,
      expectedQualifier: IrAnnotation?,
      overriddenQualifier: IrAnnotation?,
      overriddenDeclaration: IrOverridableDeclaration<*>,
      isInjector: Boolean,
    ) {
      val type =
        when {
          isInjector -> "injector function"
          declaration is IrProperty ||
            (declaration as? IrSimpleFunction)?.isPropertyAccessor == true -> "accessor property"
          else -> "accessor function"
        }

      val declWithName =
        when (overriddenDeclaration) {
          is IrSimpleFunction ->
            overriddenDeclaration.propertyIfAccessor.expectAs<IrDeclarationWithName>()
          is IrProperty -> overriddenDeclaration as IrDeclarationWithName
        }
      val message =
        "[Metro/QualifierOverrideMismatch] Overridden $type '${declaration.fqNameWhenAvailable}' must have the same qualifier annotations as the overridden $type. However, the final $type qualifier is '${expectedQualifier}' but overridden symbol ${declWithName.fqNameWhenAvailable} has '${overriddenQualifier}'.'"

      val errorDecl =
        when (declaration) {
          is IrSimpleFunction -> declaration.propertyIfAccessor
          is IrProperty -> declaration
        }

      reportCompat(
        sequenceOf(errorDecl, graphDeclaration.sourceGraphIfMetroGraph),
        MetroDiagnostics.METRO_ERROR,
        message,
      )
    }

    fun build(): DependencyGraphNode {
      if (graphDeclaration.isExternalParent || !isGraph) {
        return buildExternalGraphOrBindingContainer()
      }

      val nonNullMetroGraph = metroGraph ?: graphDeclaration.metroGraphOrFail

      val declaredScopes = computeDeclaredScopes()
      scopes += declaredScopes
      val graphExtensionSupertypes = mutableSetOf<ClassId>()

      for ((i, type) in supertypes.withIndex()) {
        val clazz = type.classOrFail.owner

        // Index 0 is this class, which we've already computed above
        if (i != 0) {
          scopes += clazz.scopeAnnotations()
          if (clazz.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionFactoryAnnotations)) {
            graphExtensionSupertypes += clazz.classIdOrFail
          }
        }

        bindingContainerTransformer.findContainer(clazz)?.let(bindingContainers::add)
      }

      // Copy inherited scopes onto this graph for faster lookups downstream
      // Note this is only for scopes inherited from supertypes, not from extended parent graphs
      val inheritedScopes = (scopes - declaredScopes).map { it.ir }
      if (graphDeclaration.origin.isInvisibleGeneratedGraph) {
        // If it's a contributed/dynamic graph, just add it directly as these are not visible to
        // metadata
        // anyway
        graphDeclaration.annotations += inheritedScopes
      } else {
        pluginContext.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
          graphDeclaration,
          inheritedScopes,
        )
      }

      for (declaration in nonNullMetroGraph.declarations) {
        // Functions and properties only
        if (declaration !is IrOverridableDeclaration<*>) continue
        if (!declaration.isFakeOverride) continue
        if (declaration is IrFunction && declaration.isInheritedFromAny(pluginContext.irBuiltIns)) {
          continue
        }
        val annotations = metroAnnotationsOf(declaration)
        if (annotations.isProvides) continue
        when (declaration) {
          is IrSimpleFunction -> {
            // Could be an injector, accessor, or graph extension
            var isGraphExtension = false
            var isOptionalBinding = annotations.isOptionalBinding
            var hasDefaultImplementation = false
            var qualifierMismatchData: Triple<IrAnnotation?, IrAnnotation?, IrSimpleFunction>? =
              null

            val isInjectorCandidate =
              declaration.regularParameters.size == 1 && !annotations.isBinds

            // Single pass through overridden symbols
            for (overridden in declaration.overriddenSymbolsSequence()) {
              if (overridden.owner.modality == Modality.OPEN || overridden.owner.body != null) {
                if (!isOptionalBinding) {
                  isOptionalBinding =
                    metroAnnotationsOf(
                        overridden.owner,
                        EnumSet.of(MetroAnnotations.Kind.OptionalBinding),
                      )
                      .isOptionalBinding
                }
                hasDefaultImplementation = true
                break
              }

              // Check for graph extension patterns
              val overriddenParentClass = overridden.owner.parentClassOrNull ?: continue
              val isGraphExtensionFactory =
                overriddenParentClass.isAnnotatedWithAny(
                  metroSymbols.classIds.graphExtensionFactoryAnnotations
                )

              if (isGraphExtensionFactory) {
                isGraphExtension = true
                // Only continue because we may ignore this if it has a default body in a parent
                continue
              }

              // Check if return type is a @GraphExtension itself (i.e. no factory)
              val returnType = overridden.owner.returnType
              val returnClass = returnType.classOrNull?.owner
              if (returnClass != null) {
                val returnsExtensionOrExtensionFactory =
                  returnClass.isAnnotatedWithAny(
                    metroSymbols.classIds.allGraphExtensionAndFactoryAnnotations
                  )
                if (returnsExtensionOrExtensionFactory) {
                  isGraphExtension = true
                  // Only continue because we may ignore this if it has a default body in a parent
                  continue
                }
              }

              // Check qualifier consistency for injectors and non-binds accessors
              if (qualifierMismatchData == null && !isGraphExtension && !annotations.isBinds) {
                val overriddenQualifier =
                  if (isInjectorCandidate) {
                    overridden.owner.regularParameters[0].qualifierAnnotation()
                  } else {
                    overridden.owner.metroAnnotations(metroSymbols.classIds).qualifier
                  }

                if (overriddenQualifier != null) {
                  val expectedQualifier =
                    if (isInjectorCandidate) {
                      // For injectors, get the qualifier from the first parameter
                      declaration.regularParameters[0].qualifierAnnotation()
                    } else {
                      // For accessors, get it from the function's annotations
                      metroAnnotationsOf(declaration).qualifier
                    }

                  if (overriddenQualifier != expectedQualifier) {
                    qualifierMismatchData =
                      Triple(expectedQualifier, overriddenQualifier, overridden.owner)
                  }
                }
              }
            }

            if (hasDefaultImplementation && !isOptionalBinding) continue

            // Report qualifier mismatch error if found
            if (qualifierMismatchData != null) {
              val (expectedQualifier, overriddenQualifier, overriddenFunction) =
                qualifierMismatchData
              reportQualifierMismatch(
                declaration,
                expectedQualifier,
                overriddenQualifier,
                overriddenFunction,
                isInjectorCandidate,
              )
            }

            val isInjector = !isGraphExtension && isInjectorCandidate

            if (isInjector && !declaration.returnType.isUnit()) {
              // FIR checks this in explicit graphs but need to account for inherited functions from
              // supertypes
              reportCompat(
                sequenceOf(declaration, graphDeclaration.sourceGraphIfMetroGraph),
                MetroDiagnostics.METRO_ERROR,
                "Injector function ${declaration.kotlinFqName} must return Unit. Or, if it's not an injector, remove its parameter.",
              )
              exitProcessing()
            }

            if (isGraphExtension) {
              val metroFunction = metroFunctionOf(declaration, annotations)
              // if the class is a factory type, need to use its parent class
              val rawType = metroFunction.ir.returnType.rawType()
              val functionParent = rawType.parentClassOrNull

              val isGraphExtensionFactory =
                rawType.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionFactoryAnnotations)

              if (isGraphExtensionFactory) {
                // For factories, add them to accessors so they participate in the binding graph
                val factoryContextKey = IrContextualTypeKey.from(declaration)
                accessors += GraphAccessor(factoryContextKey, metroFunction, false)

                // Also track it as a graph extension for metadata purposes
                val samMethod = rawType.singleAbstractFunction()
                val graphExtensionType = samMethod.returnType
                val graphExtensionTypeKey = IrTypeKey(graphExtensionType)
                if (graphExtensionTypeKey != sourceGraphTypeKey) {
                  // Only add it to our graph extensions if it's not exposing itself
                  graphExtensions.getOrPut(graphExtensionTypeKey, ::mutableListOf) +=
                    GraphExtensionAccessor(
                      accessor = metroFunction,
                      key = factoryContextKey,
                      isFactory = true,
                      isFactorySAM = false,
                    )
                }
              } else {
                // Regular graph extension
                val isSamFunction =
                  metroFunction.ir.overriddenSymbolsSequence().any {
                    it.owner.parentClassOrNull?.classId in graphExtensionSupertypes
                  }

                val contextKey =
                  if (
                    functionParent != null &&
                      functionParent.isAnnotatedWithAny(
                        metroSymbols.classIds.graphExtensionAnnotations
                      )
                  ) {
                    IrContextualTypeKey(
                      IrTypeKey(functionParent.defaultType, functionParent.qualifierAnnotation())
                    )
                  } else {
                    IrContextualTypeKey.from(declaration)
                  }
                graphExtensions.getOrPut(contextKey.typeKey, ::mutableListOf) +=
                  GraphExtensionAccessor(
                    metroFunction,
                    key = contextKey,
                    isFactory = false,
                    isFactorySAM = isSamFunction,
                  )
              }
              hasGraphExtensions = true
            } else if (isInjector) {
              // It's an injector
              val metroFunction = metroFunctionOf(declaration, annotations)
              // key is the injected type wrapped in MembersInjector
              val contextKey = IrContextualTypeKey.from(declaration.regularParameters[0])
              val memberInjectorTypeKey =
                contextKey.typeKey.copy(contextKey.typeKey.type.wrapInMembersInjector())
              val finalContextKey = contextKey.withTypeKey(memberInjectorTypeKey)

              injectors += InjectorFunction(finalContextKey, metroFunction)
            } else {
              // Accessor or binds
              val metroFunction = metroFunctionOf(declaration, annotations)
              val contextKey =
                IrContextualTypeKey.from(declaration, hasDefaultOverride = isOptionalBinding)
              if (metroFunction.annotations.isBinds) {
                bindsFunctions += (metroFunction to contextKey)
              } else {
                accessors += GraphAccessor(contextKey, metroFunction, isOptionalBinding)
              }
            }
          }

          is IrProperty -> {
            // Can only be an accessor, binds, or graph extension
            val getter = declaration.getter!!

            val rawType = getter.returnType.rawType()
            val isGraphExtensionFactory =
              rawType.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionFactoryAnnotations)
            var isGraphExtension = isGraphExtensionFactory
            var hasDefaultImplementation = false
            var isOptionalBinding = annotations.isOptionalBinding
            var qualifierMismatchData: Triple<IrAnnotation?, IrAnnotation?, IrProperty>? = null

            // Single pass through overridden symbols
            if (!isGraphExtensionFactory) {
              for (overridden in declaration.overriddenSymbolsSequence()) {
                if (
                  overridden.owner.getter?.modality == Modality.OPEN ||
                    overridden.owner.getter?.body != null
                ) {
                  if (!isOptionalBinding) {
                    isOptionalBinding =
                      metroAnnotationsOf(
                          overridden.owner,
                          EnumSet.of(MetroAnnotations.Kind.OptionalBinding),
                        )
                        .isOptionalBinding
                  }
                  hasDefaultImplementation = true
                  break
                }

                // Check if return type is a @GraphExtension or its factory
                val returnType = overridden.owner.getter?.returnType ?: continue
                val returnClass = returnType.classOrNull?.owner
                if (returnClass != null) {
                  val returnsExtension =
                    returnClass.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionAnnotations)
                  if (returnsExtension) {
                    isGraphExtension = true
                    // Don't break - continue checking qualifiers
                  }
                }

                // Check qualifier consistency for non-binds accessors
                if (qualifierMismatchData == null && !isGraphExtension && !annotations.isBinds) {
                  val overriddenGetter = overridden.owner.getter ?: continue
                  val overriddenQualifier =
                    overriddenGetter.metroAnnotations(metroSymbols.classIds).qualifier

                  if (overriddenQualifier != null) {
                    val expectedQualifier = metroAnnotationsOf(getter).qualifier

                    if (overriddenQualifier != expectedQualifier) {
                      qualifierMismatchData =
                        Triple(expectedQualifier, overriddenQualifier, overridden.owner)
                    }
                  }
                }
              }
            }

            if (hasDefaultImplementation && !isOptionalBinding) continue

            // Report qualifier mismatch error if found
            if (qualifierMismatchData != null) {
              val (expectedQualifier, overriddenQualifier, overriddenProperty) =
                qualifierMismatchData
              reportQualifierMismatch(
                declaration,
                expectedQualifier,
                overriddenQualifier,
                overriddenProperty,
                false, // properties are never injectors
              )
            }

            val metroFunction = metroFunctionOf(getter, annotations)
            val contextKey =
              IrContextualTypeKey.from(getter, hasDefaultOverride = isOptionalBinding)
            if (isGraphExtension) {
              if (isGraphExtensionFactory) {
                // For factories, add them to accessors so they participate in the binding graph
                accessors += GraphAccessor(contextKey, metroFunction, false)

                // Also track it as a graph extension for metadata purposes
                val samMethod = rawType.singleAbstractFunction()
                val graphExtensionType = samMethod.returnType
                val graphExtensionTypeKey = IrTypeKey(graphExtensionType)
                if (graphExtensionTypeKey != sourceGraphTypeKey) {
                  // Only add it to our graph extensions if it's not exposing itself
                  graphExtensions.getOrPut(graphExtensionTypeKey, ::mutableListOf) +=
                    GraphExtensionAccessor(
                      metroFunction,
                      key = contextKey,
                      isFactory = true,
                      isFactorySAM = false,
                    )
                }
              } else {
                // Regular graph extension
                val isSamFunction =
                  metroFunction.ir.overriddenSymbolsSequence().any {
                    it.owner.parentClassOrNull?.classId in graphExtensionSupertypes
                  }
                val functionParent = rawType.parentClassOrNull
                val finalContextKey =
                  if (
                    functionParent != null &&
                      functionParent.isAnnotatedWithAny(
                        metroSymbols.classIds.graphExtensionAnnotations
                      )
                  ) {
                    IrContextualTypeKey(
                      IrTypeKey(functionParent.defaultType, functionParent.qualifierAnnotation()),
                      hasDefault = isOptionalBinding,
                    )
                  } else {
                    contextKey
                  }
                graphExtensions.getOrPut(finalContextKey.typeKey, ::mutableListOf) +=
                  GraphExtensionAccessor(
                    metroFunction,
                    key = finalContextKey,
                    isFactory = false,
                    isFactorySAM = isSamFunction,
                  )
              }
              hasGraphExtensions = true
            } else {
              if (metroFunction.annotations.isBinds) {
                bindsFunctions += (metroFunction to contextKey)
              } else {
                accessors += GraphAccessor(contextKey, metroFunction, isOptionalBinding)
              }
            }
          }
        }
      }

      val creator = buildCreator()

      // Add extended node if it's a generated graph extension
      if (graphDeclaration.origin == Origins.GeneratedGraphExtension) {
        val parentGraph = graphDeclaration.parentAsClass
        val graphTypeKey = graphDeclaration.generatedGraphExtensionData!!.typeKey
        checkGraphSelfCycle(graphDeclaration, graphTypeKey, bindingStack)

        // Add its parent node
        val node =
          bindingStack.withEntry(
            IrBindingStack.Entry.generatedExtensionAt(
              IrContextualTypeKey(graphTypeKey),
              parentGraph.kotlinFqName.asString(),
            )
          ) {
            nodeCache.getOrComputeDependencyGraphNode(parentGraph, bindingStack, parentTracer)
          }
        extendedGraphNodes[node.typeKey] = node
      }

      // First, add explicitly declared binding containers from the annotation
      // (for both regular and generated graphs)
      // We compute transitives twice (heavily cached) as we want to process merging for all
      // transitively included containers
      bindingContainers +=
        dependencyGraphAnno
          ?.bindingContainerClasses(includeModulesArg = options.enableDaggerRuntimeInterop)
          .orEmpty()
          .mapNotNullToSet { it.classType.rawTypeOrNull() }
          .let(bindingContainerTransformer::resolveAllBindingContainersCached)
          .onEach { container ->
            linkDeclarationsInCompilation(graphDeclaration, container.ir)
            // Annotation-included containers may need to be managed directly
            if (container.canBeManaged) {
              managedBindingContainers += container.ir
            }
          }

      // For regular graphs (not generated extensions/dynamic), aggregate binding containers
      // from scopes using IrContributionMerger to handle merging. This can't be done in FIR
      // since we can't modify the annotation there
      if (!graphDeclaration.origin.isInvisibleGeneratedGraph && aggregationScopes.isNotEmpty()) {
        val excludes =
          dependencyGraphAnno?.excludedClasses().orEmpty().mapNotNullToSet {
            it.classType.rawTypeOrNull()?.classId
          }

        // TODO it kinda sucks that we compute this in both FIR and IR? Maybe we can do this in FIR
        //  and generate a hint/holder annotation on the graph impl
        nodeCache.contributionMerger
          .computeContributions(
            primaryScope = aggregationScopes.first(),
            allScopes = aggregationScopes,
            excluded = excludes,
          )
          ?.bindingContainers
          ?.values
          ?.let { containers ->
            // Add binding containers from merged contributions (already filtered)
            bindingContainers +=
              containers
                .mapNotNull { bindingContainerTransformer.findContainer(it) }
                .onEach { container ->
                  linkDeclarationsInCompilation(graphDeclaration, container.ir)
                  // Annotation-included containers may need to be managed directly
                  if (container.canBeManaged) {
                    managedBindingContainers += container.ir
                  }
                }
          }
      } else {
        // For generated graphs (extensions/dynamic), just resolve transitive containers
        // (no replacement filtering needed since already processed by IrContributionMerger when
        // they were generated)
      }

      // Resolve transitive binding containers
      val allMergedContainers =
        bindingContainers
          .mapToSet { it.ir }
          .let { bindingContainerTransformer.resolveAllBindingContainersCached(it) }

      for (container in allMergedContainers) {
        val isDynamicContainer = container.ir in dynamicBindingContainers
        for ((_, factory) in container.providerFactories) {
          val typeKey = factory.typeKey
          // Dynamic containers should override non-dynamic ones with the same typeKey
          val existingIsDynamic = typeKey in dynamicTypeKeys
          val existingFactory = providerFactories[typeKey]
          if (existingFactory != null) {
            // Report duplicate if both are non-dynamic OR both are dynamic
            val bothNonDynamic = !isDynamicContainer && !existingIsDynamic
            val bothDynamic = isDynamicContainer && existingIsDynamic
            if (bothNonDynamic || bothDynamic) {
              reportDuplicateProviderFactory(typeKey, existingFactory, factory)
            }
          }
          if (isDynamicContainer || !existingIsDynamic) {
            providerFactories[typeKey] = factory
          }
          if (isDynamicContainer) {
            dynamicTypeKeys[typeKey] = factory
          }
        }
        container.bindsMirror?.let { bindsMirror ->
          for (callable in bindsMirror.bindsCallables) {
            val typeKey = callable.typeKey
            // Dynamic containers should override non-dynamic ones with the same typeKey
            val existingIsDynamic = typeKey in dynamicTypeKeys
            val existingCallable = bindsCallables[typeKey]
            if (existingCallable != null) {
              // Report duplicate if both are non-dynamic OR both are dynamic
              val bothNonDynamic = !isDynamicContainer && !existingIsDynamic
              val bothDynamic = isDynamicContainer && existingIsDynamic
              if (bothNonDynamic || bothDynamic) {
                reportDuplicateBindsCallable(typeKey, existingCallable, callable)
              }
            }
            if (isDynamicContainer || !existingIsDynamic) {
              bindsCallables[typeKey] = callable
            }
            if (isDynamicContainer) {
              dynamicTypeKeys[typeKey] = callable
            }
          }
          for (callable in bindsMirror.multibindsCallables) {
            multibindsCallables += callable
            if (isDynamicContainer) {
              dynamicTypeKeys[callable.typeKey] = callable
            }
          }
          for (callable in bindsMirror.optionalKeys) {
            optionalKeys.getOrPut(callable.typeKey, ::mutableSetOf) += callable
            if (isDynamicContainer) {
              dynamicTypeKeys[callable.typeKey] = callable
            }
          }
        }

        // Record an IC lookup of the container class
        trackClassLookup(graphDeclaration, container.ir)
      }

      writeDiagnostic("bindingContainers-${parentTracer.tag}.txt") {
        allMergedContainers.joinToString("\n") { it.ir.classId.toString() }
      }

      val dependencyGraphNode =
        DependencyGraphNode(
          sourceGraph = graphDeclaration,
          supertypes = supertypes.toList(),
          includedGraphNodes = includedGraphNodes,
          graphExtensions = graphExtensions,
          scopes = scopes,
          aggregationScopes = aggregationScopes,
          bindsCallables = bindsCallables,
          bindsFunctions = bindsFunctions.map { it.first },
          multibindsCallables = multibindsCallables,
          optionalKeys = optionalKeys,
          providerFactories = providerFactories,
          accessors = accessors,
          injectors = injectors,
          isExternal = false,
          creator = creator,
          extendedGraphNodes = extendedGraphNodes,
          bindingContainers = managedBindingContainers,
          dynamicTypeKeys = dynamicTypeKeys,
          typeKey = graphTypeKey,
        )

      // Check after creating a node for access to recursive allDependencies
      val overlapErrors = mutableSetOf<String>()
      for (depNode in dependencyGraphNode.allExtendedNodes.values) {
        // If any intersect, report an error to onError with the intersecting types (including
        // which parent it is coming from)
        val overlaps = scopes.intersect(depNode.scopes)
        if (overlaps.isNotEmpty()) {
          for (overlap in overlaps) {
            overlapErrors +=
              "- ${overlap.render(short = false)} (from ancestor '${depNode.sourceGraph.kotlinFqName}')"
          }
        }
      }
      if (overlapErrors.isNotEmpty()) {
        metroContext.reportCompat(
          graphDeclaration,
          MetroDiagnostics.METRO_ERROR,
          buildString {
            appendLine(
              "Graph extension '${dependencyGraphNode.sourceGraph.sourceGraphIfMetroGraph.kotlinFqName}' has overlapping scope annotations with ancestor graphs':"
            )
            for (overlap in overlapErrors) {
              appendLine(overlap)
            }
          },
        )
        exitProcessing()
      }

      // Exit after collecting all duplicate binding errors
      if (hasDuplicateBindingErrors) {
        exitProcessing()
      }

      return dependencyGraphNode
    }

    private fun buildExternalGraphOrBindingContainer(): DependencyGraphNode {
      // Read metadata if this is an extendable graph
      val includedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
      val accessorsToCheck =
        if (isGraph) {
          // It's just an external graph, just read the declared types from it
          graphDeclaration
            .metroGraphOrFail // Doesn't cover contributed graphs but they're not visible anyway
            .allCallableMembers(
              excludeInheritedMembers = false,
              excludeCompanionObjectMembers = true,
            )
        } else {
          // Track overridden symbols so that we dedupe merged overrides in the final class
          val seenSymbols = mutableSetOf<IrSymbol>()
          // TODO single supertype pass
          supertypes.flatMap { type ->
            type
              .rawType()
              .allCallableMembers(
                excludeInheritedMembers = false,
                excludeCompanionObjectMembers = true,
                functionFilter = { it.symbol !in seenSymbols },
                propertyFilter = {
                  val getterSymbol = it.getter?.symbol
                  getterSymbol != null && getterSymbol !in seenSymbols
                },
              )
              .onEach { seenSymbols += it.ir.overriddenSymbolsSequence() }
          }
        }

      accessors +=
        accessorsToCheck
          .filter { it.isAccessorCandidate }
          .map { metroFunction ->
            GraphAccessor(
              IrContextualTypeKey.from(metroFunction.ir),
              metroFunction,
              metroFunction.annotations.isOptionalBinding,
            )
          }

      // TODO only if annotated @BindingContainer?
      // TODO need to look up accessors and binds functions
      if (isGraph) {
        // TODO is this duplicating info we already have in the proto?
        for (type in supertypes) {
          val declaration = type.classOrNull?.owner ?: continue
          // Skip the metrograph, it won't have custom nested factories
          if (declaration == metroGraph) continue
          bindingContainerTransformer.findContainer(declaration)?.let { bindingContainer ->
            for ((_, factory) in bindingContainer.providerFactories) {
              providerFactories[factory.typeKey] = factory
            }

            bindingContainer.bindsMirror?.let { bindsMirror ->
              bindsCallables.putAll(bindsMirror.bindsCallables.associateBy { it.typeKey })
              multibindsCallables += bindsMirror.multibindsCallables
              for (callable in bindsMirror.optionalKeys) {
                optionalKeys.getOrPut(callable.typeKey) { mutableSetOf() } += callable
              }
            }
          }
        }
      } else {
        providerFactories.putAll(
          bindingContainerTransformer.factoryClassesFor(metroGraph ?: graphDeclaration)
        )
      }

      // TODO split DependencyGraphNode into sealed interface with external/internal variants?
      val dependentNode =
        DependencyGraphNode(
          sourceGraph = graphDeclaration,
          supertypes = supertypes.toList(),
          includedGraphNodes = includedGraphNodes,
          scopes = scopes,
          aggregationScopes = aggregationScopes,
          providerFactories = providerFactories,
          accessors = accessors,
          bindsCallables = bindsCallables,
          multibindsCallables = multibindsCallables,
          optionalKeys = optionalKeys,
          isExternal = true,
          proto = null,
          extendedGraphNodes = extendedGraphNodes,
          // Following aren't necessary to see in external graphs
          graphExtensions = emptyMap(),
          injectors = emptyList(),
          creator = null,
          bindingContainers = emptySet(),
          bindsFunctions = emptyList(),
          dynamicTypeKeys = emptyMap(),
        )

      return dependentNode
    }
  }
}
