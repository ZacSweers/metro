// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.BitField
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInMembersInjector
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.memoized
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrClass
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
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId

internal class DependencyGraphNodeCache(
  metroContext: IrMetroContext,
  private val contributionData: IrContributionData,
  private val bindingContainerTransformer: BindingContainerTransformer,
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
    if (graphDeclaration.origin != Origins.GeneratedGraphExtension) {
      val sourceGraph = graphDeclaration.sourceGraphIfMetroGraph
      if (sourceGraph != graphDeclaration) {
        return getOrComputeDependencyGraphNode(
          sourceGraph,
          bindingStack,
          parentTracer,
          metroGraph,
          dependencyGraphAnno
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
    private val accessors = mutableListOf<Pair<MetroSimpleFunction, IrContextualTypeKey>>()
    private val bindsFunctions = mutableListOf<Pair<MetroSimpleFunction, IrContextualTypeKey>>()
    private val bindsCallables = mutableSetOf<BindsCallable>()
    private val multibindsCallables = mutableSetOf<MultibindsCallable>()
    private val scopes = mutableSetOf<IrAnnotation>()
    private val providerFactories = mutableListOf<Pair<IrTypeKey, ProviderFactory>>()
    private val extendedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
    private val graphExtensions = mutableMapOf<IrTypeKey, MutableList<GraphExtensionAccessor>>()
    private val injectors = mutableListOf<Pair<MetroSimpleFunction, IrContextualTypeKey>>()
    private val includedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
    private val graphTypeKey = IrTypeKey(graphDeclaration.typeWith())
    private val graphContextKey = IrContextualTypeKey.create(graphTypeKey)
    private val bindingContainers = mutableSetOf<BindingContainer>()

    private val dependencyGraphAnno =
      cachedDependencyGraphAnno
        ?: graphDeclaration.annotationsIn(symbols.dependencyGraphAnnotations).singleOrNull()
    private val aggregationScopes = mutableSetOf<ClassId>()
    private val isGraph = dependencyGraphAnno != null
    private val supertypes =
      (metroGraph ?: graphDeclaration).getAllSuperTypes(excludeSelf = false).memoized()
    private val contributionData = nodeCache.contributionData

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
              irCall(symbols.metroSingleInConstructor).apply { arguments[0] = scopeArg }
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
                  irCall(symbols.metroSingleInConstructor).apply {
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

            if (parameterClass.isAnnotatedWithAny(symbols.classIds.bindingContainerAnnotations)) {
              bindingContainerFields = bindingContainerFields.withSet(i)
            }
          }
        }
      }

      val creator =
        if (graphDeclaration.origin === Origins.GeneratedGraphExtension) {
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
              klass.isAnnotatedWithAny(symbols.dependencyGraphFactoryAnnotations)
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

          val klass = parameter.typeKey.type.rawType()
          val sourceGraph = klass.sourceGraphIfMetroGraph

          checkGraphSelfCycle(graphDeclaration, graphTypeKey, bindingStack)

          // Add any included graph provider factories IFF it's a binding container
          if (nonNullCreator.bindingContainersParameterIndices.isSet(i)) {
            val bindingContainer =
              bindingContainerTransformer.findContainer(sourceGraph)
                ?: reportCompilerBug("Binding container not found for type ${sourceGraph.classId}")

            bindingContainers += bindingContainer
            return@forEachIndexed
          }

          // It's a graph-like
          val node =
            bindingStack.withEntry(
              IrBindingStack.Entry.injectedAt(graphContextKey, nonNullCreator.function)
            ) {
              val nodeKey =
                if (klass.origin == Origins.GeneratedGraphExtension) {
                  klass
                } else {
                  sourceGraph
                }
              nodeCache.getOrComputeDependencyGraphNode(nodeKey, bindingStack, parentTracer)
            }

          // Still tie to the parameter key because that's what gets the instance binding
          if (parameter.isIncludes) {
            includedGraphNodes[parameter.typeKey] = node
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
        diagnosticReporter
          .at(graphDeclaration)
          .report(MetroIrErrors.GRAPH_DEPENDENCY_CYCLE, message)
        exitProcessing()
      }
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
          if (clazz.isAnnotatedWithAny(symbols.classIds.graphExtensionFactoryAnnotations)) {
            graphExtensionSupertypes += clazz.classIdOrFail
          }
        }

        bindingContainerTransformer.findContainer(clazz)?.let(bindingContainers::add)
      }

      // Copy inherited scopes onto this graph for faster lookups downstream
      // Note this is only for scopes inherited from supertypes, not from extended parent graphs
      val inheritedScopes = (scopes - declaredScopes).map { it.ir }
      if (graphDeclaration.origin === Origins.GeneratedGraphExtension) {
        // If it's a contributed graph, just add it directly as these are not visible to metadata
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

            // If the overridden symbol has a default getter/value then skip
            var hasDefaultImplementation = false
            for (overridden in declaration.overriddenSymbolsSequence()) {
              if (overridden.owner.body != null) {
                hasDefaultImplementation = true
                break
              }

              val overriddenParentClass = overridden.owner.parentClassOrNull ?: continue
              val isGraphExtensionFactory =
                overriddenParentClass.isAnnotatedWithAny(
                  symbols.classIds.graphExtensionFactoryAnnotations
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
                    symbols.classIds.allGraphExtensionAndFactoryAnnotations
                  )
                if (returnsExtensionOrExtensionFactory) {
                  isGraphExtension = true
                  // Only continue because we may ignore this if it has a default body in a parent
                  continue
                }
              }
            }
            if (hasDefaultImplementation) continue

            val isInjector =
              !isGraphExtension &&
                declaration.regularParameters.size == 1 &&
                !annotations.isBinds &&
                declaration.returnType.isUnit()
            if (isGraphExtension) {
              val metroFunction = metroFunctionOf(declaration, annotations)
              // if the class is a factory type, need to use its parent class
              val rawType = metroFunction.ir.returnType.rawType()
              val functionParent = rawType.parentClassOrNull

              val isGraphExtensionFactory = rawType.isAnnotatedWithAny(
                symbols.classIds.graphExtensionFactoryAnnotations
              )

              if (isGraphExtensionFactory) {
                // For factories, add them to accessors so they participate in the binding graph
                val factoryContextKey = IrContextualTypeKey.from(declaration)
                accessors += (metroFunction to factoryContextKey)

                // Also track it as a graph extension for metadata purposes
                val samMethod = rawType.singleAbstractFunction()
                val graphExtensionType = samMethod.returnType
                val graphExtensionTypeKey = IrTypeKey(graphExtensionType)
                graphExtensions.getOrPut(graphExtensionTypeKey, ::mutableListOf) += GraphExtensionAccessor(
                  accessor = metroFunction,
                  key = factoryContextKey,
                  isFactory = true,
                  isFactorySAM = false,
                )
              } else {
                // Regular graph extension
                val isSamFunction = metroFunction.ir.overriddenSymbolsSequence()
                  .any { it.owner.parentClassOrNull?.classId in graphExtensionSupertypes }

                val contextKey =
                  if (
                    functionParent != null &&
                      functionParent.isAnnotatedWithAny(symbols.classIds.graphExtensionAnnotations)
                  ) {
                    IrContextualTypeKey(
                      IrTypeKey(functionParent.defaultType, functionParent.qualifierAnnotation())
                    )
                  } else {
                    IrContextualTypeKey.from(declaration)
                  }
                graphExtensions.getOrPut(contextKey.typeKey, ::mutableListOf) += GraphExtensionAccessor(
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
              injectors += (metroFunction to finalContextKey)
            } else {
              // Accessor or binds
              val metroFunction = metroFunctionOf(declaration, annotations)
              val contextKey = IrContextualTypeKey.from(declaration)
              val collection =
                if (metroFunction.annotations.isBinds) {
                  bindsFunctions
                } else {
                  accessors
                }
              collection += (metroFunction to contextKey)
            }
          }

          is IrProperty -> {
            // Can only be an accessor, binds, or graph extension
            val getter = declaration.getter!!

            val rawType = getter.returnType.rawType()
            val isGraphExtensionFactory = rawType.isAnnotatedWithAny(
              symbols.classIds.graphExtensionFactoryAnnotations
            )
            var isGraphExtension = isGraphExtensionFactory

            // If the overridden symbol has a default getter/value then skip
            var hasDefaultImplementation = false
            if (!isGraphExtensionFactory) {
              for (overridden in declaration.overriddenSymbolsSequence()) {
                if (overridden.owner.getter?.body != null) {
                  hasDefaultImplementation = true
                  break
                }

                // Check if return type is a @GraphExtension or its factory
                val returnType = overridden.owner.getter?.returnType ?: continue
                val returnClass = returnType.classOrNull?.owner
                if (returnClass != null) {
                  val returnsExtension =
                    returnClass.isAnnotatedWithAny(symbols.classIds.graphExtensionAnnotations)
                  if (returnsExtension) {
                    isGraphExtension = true
                    break
                  }
                }
              }
            }
            if (hasDefaultImplementation) continue

            val metroFunction = metroFunctionOf(getter, annotations)
            val contextKey = IrContextualTypeKey.from(getter)
            if (isGraphExtension) {
              if (isGraphExtensionFactory) {
                // For factories, add them to accessors so they participate in the binding graph
                accessors += (metroFunction to contextKey)

                // Also track it as a graph extension for metadata purposes
                val samMethod = rawType.singleAbstractFunction()
                val graphExtensionType = samMethod.returnType
                val graphExtensionTypeKey = IrTypeKey(graphExtensionType)
                graphExtensions.getOrPut(graphExtensionTypeKey, ::mutableListOf) += GraphExtensionAccessor(
                  metroFunction,
                  key = contextKey,
                  isFactory = true,
                  isFactorySAM = false,
                )
              } else {
                // Regular graph extension
                val isSamFunction = metroFunction.ir.overriddenSymbolsSequence()
                  .any { it.owner.parentClassOrNull?.classId in graphExtensionSupertypes }
                val functionParent = rawType.parentClassOrNull
                val finalContextKey =
                  if (
                    functionParent != null &&
                      functionParent.isAnnotatedWithAny(symbols.classIds.graphExtensionAnnotations)
                  ) {
                    IrContextualTypeKey(
                      IrTypeKey(functionParent.defaultType, functionParent.qualifierAnnotation())
                    )
                  } else {
                    contextKey
                  }
                graphExtensions.getOrPut(finalContextKey.typeKey, ::mutableListOf) += GraphExtensionAccessor(
                  metroFunction,
                  key = finalContextKey,
                  isFactory = false,
                  isFactorySAM = isSamFunction,
                )
              }
              hasGraphExtensions = true
            } else {
              val collection =
                if (metroFunction.annotations.isBinds) {
                  bindsFunctions
                } else {
                  accessors
                }
              collection += (metroFunction to contextKey)
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

      val managedBindingContainers = mutableSetOf<IrClass>()
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

      val excludes =
        dependencyGraphAnno?.excludedClasses().orEmpty().mapNotNullToSet {
          it.classType.rawTypeOrNull()?.classId
        }

      for (scope in aggregationScopes) {
        bindingContainers +=
          contributionData
            .getBindingContainerContributions(scope)
            .mapNotNull { bindingContainerTransformer.findContainer(it) }
            .filterNot { it.ir.classId in excludes }
            .onEach { container ->
              // Annotation-included containers may need to be managed directly
              if (container.canBeManaged) {
                managedBindingContainers += container.ir
              }
            }
      }

      // TODO this doesn't cover replaced class bindings/other types
      val replaced =
        bindingContainers.flatMapToSet { container ->
          container.ir
            .annotationsIn(symbols.classIds.contributesToAnnotations)
            .firstOrNull { it.scopeOrNull() in aggregationScopes }
            ?.replacedClasses()
            ?.mapNotNullToSet { replacedClass -> replacedClass.classType.rawTypeOrNull()?.classId }
            .orEmpty()
        }
      val mergedContainers = bindingContainers.filterNot { it.ir.classId in replaced }

      for (container in mergedContainers) {
        providerFactories += container.providerFactories.values.map { it.typeKey to it }
        container.bindsMirror?.let { bindsMirror ->
          bindsCallables += bindsMirror.bindsCallables
          multibindsCallables += bindsMirror.multibindsCallables
        }

        // Record an IC lookup of the container class
        trackClassLookup(graphDeclaration, container.ir)
      }

      writeDiagnostic("bindingContainers-${parentTracer.tag}.txt") {
        mergedContainers.joinToString("\n") { it.ir.classId.toString() }
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
          providerFactories = providerFactories,
          accessors = accessors,
          injectors = injectors,
          isExternal = false,
          creator = creator,
          extendedGraphNodes = extendedGraphNodes,
          bindingContainers = managedBindingContainers,
          typeKey = graphTypeKey,
        )

      // Check after creating a node for access to recursive allDependencies
      val overlapErrors = mutableSetOf<String>()
      val seenAncestorScopes = mutableMapOf<IrAnnotation, DependencyGraphNode>()
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
        for (parentScope in depNode.scopes) {
          seenAncestorScopes.put(parentScope, depNode)?.let { previous ->
            diagnosticReporter
              .at(graphDeclaration)
              .report(
                MetroIrErrors.METRO_ERROR,
                buildString {
                  appendLine(
                    "Graph extensions (@Extends) may not have multiple ancestors with the same scopes:"
                  )
                  append("Scope: ")
                  appendLine(parentScope.render(short = false))
                  append("Ancestor 1: ")
                  appendLine(previous.sourceGraph.kotlinFqName)
                  append("Ancestor 2: ")
                  appendLine(depNode.sourceGraph.kotlinFqName)
                },
              )
            exitProcessing()
          }
        }
      }
      if (overlapErrors.isNotEmpty()) {
        val message = buildString {
          appendLine(
            "Graph extension '${dependencyGraphNode.sourceGraph.sourceGraphIfMetroGraph.kotlinFqName}' has overlapping scope annotations with ancestor graphs':"
          )
          for (overlap in overlapErrors) {
            appendLine(overlap)
          }
        }

        // TODO in 2.2.20 use just diagnostic reporter
        if (graphDeclaration.origin === Origins.GeneratedGraphExtension) {
          messageCollector.report(
            CompilerMessageSeverity.ERROR,
            message,
            graphDeclaration.locationOrNull(),
          )
        } else {
          diagnosticReporter.at(graphDeclaration).report(MetroIrErrors.METRO_ERROR, message)
        }
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
          .map { it to IrContextualTypeKey.from(it.ir) }

      // TODO only if annotated @BindingContainer?
      // TODO need to look up accessors and binds functions
      if (isGraph) {
        // TODO is this duplicating info we already have in the proto?
        for (type in supertypes) {
          val declaration = type.classOrNull?.owner ?: continue
          // Skip the metrograph, it won't have custom nested factories
          if (declaration == metroGraph) continue
          bindingContainerTransformer.findContainer(declaration)?.let { bindingContainer ->
            providerFactories += bindingContainer.providerFactories.values.map { it.typeKey to it }

            bindingContainer.bindsMirror?.let { bindsMirror ->
              bindsCallables += bindsMirror.bindsCallables
              multibindsCallables += bindsMirror.multibindsCallables
            }
          }
        }
      } else if (!isGraph) {
        providerFactories +=
          bindingContainerTransformer.factoryClassesFor(metroGraph ?: graphDeclaration)
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
          isExternal = true,
          proto = null,
          extendedGraphNodes = extendedGraphNodes,
          // Following aren't necessary to see in external graphs
          graphExtensions = emptyMap(),
          injectors = emptyList(),
          creator = null,
          bindingContainers = emptySet(),
          bindsFunctions = emptyList(),
        )

      return dependentNode
    }
  }
}
