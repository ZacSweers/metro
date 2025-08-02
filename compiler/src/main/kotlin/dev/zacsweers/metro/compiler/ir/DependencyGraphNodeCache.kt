// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.BitField
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInMembersInjector
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.memoized
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
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
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
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
    private val contributedGraphs = mutableMapOf<IrTypeKey, MetroSimpleFunction>()
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

    private val isExtendable = dependencyGraphAnno?.isExtendable() ?: false

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
        if (graphDeclaration.origin === Origins.ContributedGraph) {
          val ctor = graphDeclaration.primaryConstructor!!
          val ctorParams = ctor.parameters()
          populateBindingContainerFields(ctorParams)
          DependencyGraphNode.Creator.Constructor(
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

          val type = parameter.typeKey.type.rawType()

          checkGraphSelfCycle(graphDeclaration, graphTypeKey, bindingStack)

          // Add any included graph provider factories IFF it's a binding container
          if (nonNullCreator.bindingContainersParameterIndices.isSet(i)) {
            val bindingContainer =
              bindingContainerTransformer.findContainer(type)
                ?: error("Binding container not found for type ${type.classId}")

            bindingContainers += bindingContainer
            return@forEachIndexed
          }

          // It's a graph-like
          val node =
            bindingStack.withEntry(
              IrBindingStack.Entry.requestedAt(graphContextKey, nonNullCreator.function)
            ) {
              nodeCache.getOrComputeDependencyGraphNode(type, bindingStack, parentTracer)
            }

          if (parameter.isExtends) {
            extendedGraphNodes[parameter.typeKey] = node
          } else {
            // parameter.isIncludes
            includedGraphNodes[parameter.typeKey] = node
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
          .report(MetroDiagnostics.GRAPH_DEPENDENCY_CYCLE, message)
        exitProcessing()
      }
    }

    fun build(): DependencyGraphNode {
      if (graphDeclaration.isExternalParent || !isGraph) {
        return buildExternalGraphOrBindingContainer()
      }

      val nonNullMetroGraph = metroGraph ?: graphDeclaration.metroGraphOrFail

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
            // Could be an injector, accessor, or contributed graph
            var isContributedGraph = false

            // If the overridden symbol has a default getter/value then skip
            var hasDefaultImplementation = false
            for (overridden in declaration.overriddenSymbolsSequence()) {
              if (overridden.owner.body != null) {
                hasDefaultImplementation = true
                break
              } else if (
                overridden.owner.parentClassOrNull?.isAnnotatedWithAny(
                  symbols.classIds.contributesGraphExtensionFactoryAnnotations
                ) == true
              ) {
                isContributedGraph = true
                break
              }
            }
            if (hasDefaultImplementation) continue

            val isInjector =
              !isContributedGraph &&
                declaration.regularParameters.size == 1 &&
                !annotations.isBinds &&
                declaration.returnType.isUnit()
            if (isContributedGraph) {
              val metroFunction = metroFunctionOf(declaration, annotations)
              val contextKey = IrContextualTypeKey.from(declaration)
              contributedGraphs[contextKey.typeKey] = metroFunction
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
            // Can only be an accessor, binds, or contributed graph
            var isContributedGraph = false

            // If the overridden symbol has a default getter/value then skip
            var hasDefaultImplementation = false
            for (overridden in declaration.overriddenSymbolsSequence()) {
              if (overridden.owner.getter?.body != null) {
                hasDefaultImplementation = true
                break
              } else if (
                overridden.owner.parentClassOrNull?.isAnnotatedWithAny(
                  symbols.classIds.contributesGraphExtensionFactoryAnnotations
                ) == true
              ) {
                isContributedGraph = true
                break
              }
            }
            if (hasDefaultImplementation) continue

            val getter = declaration.getter!!
            val metroFunction = metroFunctionOf(getter, annotations)
            val contextKey = IrContextualTypeKey.from(getter)
            if (isContributedGraph) {
              contributedGraphs[contextKey.typeKey] = metroFunction
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

      val declaredScopes = computeDeclaredScopes()
      scopes += declaredScopes

      for ((i, type) in supertypes.withIndex()) {
        val clazz = type.classOrFail.owner

        // Index 0 is this class, which we've already computed above
        if (i != 0) {
          scopes += clazz.scopeAnnotations()
        }

        bindingContainerTransformer.findContainer(clazz)?.let(bindingContainers::add)
      }

      if (isExtendable) {
        // Copy inherited scopes onto this graph for faster lookups downstream
        // Note this is only for scopes inherited from supertypes, not from extended parent graphs
        val inheritedScopes = (scopes - declaredScopes).map { it.ir }
        if (graphDeclaration.origin === Origins.ContributedGraph) {
          // If it's a contributed graph, just add it directly as these are not visible to metadata
          // anyway
          graphDeclaration.annotations += inheritedScopes
        } else {
          pluginContext.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
            graphDeclaration,
            inheritedScopes,
          )
        }
      }

      val creator = buildCreator()

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
          isExtendable = isExtendable,
          includedGraphNodes = includedGraphNodes,
          contributedGraphs = contributedGraphs,
          scopes = scopes,
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
                MetroDiagnostics.METRO_ERROR,
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
        diagnosticReporter
          .at(graphDeclaration)
          .report(
            MetroDiagnostics.METRO_ERROR,
            buildString {
              appendLine(
                "Graph extensions (@Extends) may not have overlapping scopes with its ancestor graphs but the following scopes overlap:"
              )
              for (overlap in overlapErrors) {
                appendLine(overlap)
              }
            },
          )
        exitProcessing()
      }

      return dependencyGraphNode
    }

    private fun buildExternalGraphOrBindingContainer(): DependencyGraphNode {
      // Read metadata if this is an extendable graph
      val includedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
      var graphProto: DependencyGraphProto? = null
      if (isExtendable) {
        parentTracer.traceNested("Populate inherited graph metadata") { tracer ->
          val serialized =
            pluginContext.metadataDeclarationRegistrar.getCustomMetadataExtension(
              graphDeclaration.requireNestedClass(Symbols.Names.MetroGraph),
              PLUGIN_ID,
            )
          if (serialized == null) {
            diagnosticReporter
              .at(graphDeclaration)
              .report(
                MetroDiagnostics.METRO_ERROR,
                "Missing metadata for extendable graph ${graphDeclaration.kotlinFqName}. Was this compiled by the Metro compiler?",
              )
            exitProcessing()
          }

          graphProto =
            tracer.traceNested("Deserialize DependencyGraphProto") {
              val metadata = MetroMetadata.ADAPTER.decode(serialized)
              metadata.dependency_graph
            }
          if (graphProto == null) {
            diagnosticReporter
              .at(graphDeclaration)
              .report(
                MetroDiagnostics.METRO_ERROR,
                "Missing graph data for extendable graph ${graphDeclaration.kotlinFqName}. Was this compiled by the Metro compiler?",
              )
            exitProcessing()
          }

          // Read scopes from annotations
          // We copy scope annotations from parents onto this graph if it's extendable so we only
          // need to copy once
          scopes.addAll(graphDeclaration.scopeAnnotations())

          includedGraphNodes.putAll(
            // TODO dedupe logic with below
            graphProto.included_classes.associate { graphClassId ->
              val clazz =
                pluginContext.referenceClass(ClassId.fromString(graphClassId))
                  ?: error("Could not find graph class $graphClassId.")
              val typeKey = IrTypeKey(clazz.defaultType)
              val node =
                nodeCache.getOrComputeDependencyGraphNode(clazz.owner, bindingStack, parentTracer)
              typeKey to node
            }
          )

          extendedGraphNodes.putAll(
            graphProto.parent_graph_classes.associate { graphClassId ->
              val clazz =
                pluginContext.referenceClass(ClassId.fromString(graphClassId))
                  ?: error("Could not find graph class $graphClassId.")
              val typeKey = IrTypeKey(clazz.defaultType)
              val node =
                nodeCache.getOrComputeDependencyGraphNode(clazz.owner, bindingStack, parentTracer)
              typeKey to node
            }
          )
        }
      }

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
      if (isGraph && isExtendable) {
        // TODO is this duplicating info we already have in the proto?
        for (type in supertypes) {
          val declaration = type.classOrNull?.owner ?: continue
          // Skip the metrograph, it won't have custom nested factories
          if (declaration == metroGraph) continue
          val proto = if (declaration == graphDeclaration) graphProto else null
          bindingContainerTransformer.findContainer(declaration, graphProto = proto)?.let {
            bindingContainer ->
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
          isExtendable = isExtendable,
          includedGraphNodes = includedGraphNodes,
          scopes = scopes,
          providerFactories = providerFactories,
          accessors = accessors,
          bindsCallables = bindsCallables,
          multibindsCallables = multibindsCallables,
          isExternal = true,
          proto = graphProto,
          extendedGraphNodes = extendedGraphNodes,
          // Following aren't necessary to see in external graphs
          contributedGraphs = contributedGraphs,
          injectors = injectors,
          creator = null,
          // External viewers don't look at this
          bindingContainers = emptySet(),
          bindsFunctions = emptyList(),
        )

      return dependentNode
    }
  }
}
