package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInMembersInjector
import dev.zacsweers.metro.compiler.ir.transformers.ProvidesTransformer
import dev.zacsweers.metro.compiler.memoized
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import kotlin.collections.plus
import kotlin.collections.plusAssign
import kotlin.collections.set
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

internal class DependencyGraphNodes(
  metroContext: IrMetroContext,
  private val providesTransformer: ProvidesTransformer,
) : IrMetroContext by metroContext {

  // Keyed by the source declaration
  // TODO make private
  val dependencyGraphNodesByClass = mutableMapOf<ClassId, DependencyGraphNode>()

  fun getOrComputeDependencyGraphNode(
    graphDeclaration: IrClass,
    bindingStack: IrBindingStack,
    parentTracer: Tracer,
    metroGraph: IrClass? = null,
    dependencyGraphAnno: IrConstructorCall? = null,
  ): DependencyGraphNode {
    val graphClassId = graphDeclaration.classIdOrFail
    dependencyGraphNodesByClass[graphClassId]?.let {
      return it
    }

    val node =
      parentTracer.traceNested("Build DependencyGraphNode") { tracer ->
        computeDependencyGraphNode(
          graphClassId,
          graphDeclaration,
          bindingStack,
          tracer,
          metroGraph,
          dependencyGraphAnno,
        )
      }

    dependencyGraphNodesByClass[graphClassId] = node

    return node
  }

  fun computeDependencyGraphNode(
    graphClassId: ClassId,
    graphDeclaration: IrClass,
    bindingStack: IrBindingStack,
    parentTracer: Tracer,
    metroGraph: IrClass? = null,
    dependencyGraphAnno: IrConstructorCall? = null,
  ): DependencyGraphNode {
    val dependencyGraphAnno =
      dependencyGraphAnno
        ?: graphDeclaration.annotationsIn(symbols.dependencyGraphAnnotations).singleOrNull()
    val isGraph = dependencyGraphAnno != null
    val supertypes =
      (graphDeclaration.metroGraphOrNull ?: graphDeclaration)
        .getAllSuperTypes(pluginContext, excludeSelf = false)
        .memoized()

    val accessors = mutableListOf<Pair<MetroSimpleFunction, IrContextualTypeKey>>()
    val bindsFunctions = mutableListOf<Pair<MetroSimpleFunction, IrContextualTypeKey>>()
    val scopes = mutableSetOf<IrAnnotation>()
    val providerFactories = mutableListOf<Pair<IrTypeKey, ProviderFactory>>()
    val extendedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
    val contributedGraphs = mutableMapOf<IrTypeKey, MetroSimpleFunction>()
    val injectors = mutableListOf<Pair<MetroSimpleFunction, IrContextualTypeKey>>()

    val isExtendable =
      dependencyGraphAnno?.getConstBooleanArgumentOrNull(Symbols.Names.isExtendable) == true

    if (graphDeclaration.isExternalParent || !isGraph) {
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
          .filterNot { it.isAccessorCandidate }
          .map { it to IrContextualTypeKey.from(it.ir) }

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
                MetroIrErrors.METRO_ERROR,
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
                MetroIrErrors.METRO_ERROR,
                "Missing graph data for extendable graph ${graphDeclaration.kotlinFqName}. Was this compiled by the Metro compiler?",
              )
            exitProcessing()
          }

          // Add any provider factories
          providerFactories +=
            graphProto.provider_factory_classes
              .map { classId ->
                val clazz = pluginContext.referenceClass(ClassId.fromString(classId))!!.owner
                providesTransformer.externalProviderFactoryFor(clazz)
              }
              .map { it.typeKey to it }

          // Add any binds functions
          bindsFunctions.addAll(
            graphProto.binds_callable_ids.map { bindsCallableId ->
              val classId = ClassId.fromString(bindsCallableId.class_id)
              val callableId = CallableId(classId, bindsCallableId.callable_name.asName())

              val function =
                if (bindsCallableId.is_property) {
                  pluginContext.referenceProperties(callableId).singleOrNull()?.owner?.getter
                } else {
                  pluginContext.referenceFunctions(callableId).singleOrNull()?.owner
                }

              if (function == null) {
                val message = buildString {
                  append("No function found for ")
                  appendLine(callableId)
                  callableId.classId?.let {
                    pluginContext.referenceClass(it)?.let {
                      appendLine("Class dump")
                      appendLine(it.owner.dumpKotlinLike())
                    }
                  }
                    ?: run {
                      append("No class found for ")
                      appendLine(callableId)
                    }
                }
                error(message)
              }

              val metroFunction = metroFunctionOf(function)
              metroFunction to IrContextualTypeKey.from(this, function)
            }
          )

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
              val node = getOrComputeDependencyGraphNode(clazz.owner, bindingStack, parentTracer)
              typeKey to node
            }
          )

          extendedGraphNodes.putAll(
            graphProto.parent_graph_classes.associate { graphClassId ->
              val clazz =
                pluginContext.referenceClass(ClassId.fromString(graphClassId))
                  ?: error("Could not find graph class $graphClassId.")
              val typeKey = IrTypeKey(clazz.defaultType)
              val node = getOrComputeDependencyGraphNode(clazz.owner, bindingStack, parentTracer)
              typeKey to node
            }
          )
        }
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
          bindsFunctions = bindsFunctions,
          isExternal = true,
          proto = graphProto,
          extendedGraphNodes = extendedGraphNodes,
          // Following aren't necessary to see in external graphs
          contributedGraphs = contributedGraphs,
          injectors = injectors,
          creator = null,
        )

      dependencyGraphNodesByClass[graphClassId] = dependentNode

      return dependentNode
    }

    val nonNullMetroGraph = metroGraph ?: graphDeclaration.metroGraphOrFail
    val graphTypeKey = IrTypeKey(graphDeclaration.typeWith())
    val graphContextKey = IrContextualTypeKey.create(graphTypeKey)

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
            val contextKey = IrContextualTypeKey.from(this, declaration)
            contributedGraphs[contextKey.typeKey] = metroFunction
          } else if (isInjector) {
            // It's an injector
            val metroFunction = metroFunctionOf(declaration, annotations)
            // key is the injected type wrapped in MembersInjector
            val contextKey = IrContextualTypeKey.from(this, declaration.regularParameters[0])
            val memberInjectorTypeKey =
              contextKey.typeKey.copy(contextKey.typeKey.type.wrapInMembersInjector())
            val finalContextKey = contextKey.withTypeKey(memberInjectorTypeKey)
            injectors += (metroFunction to finalContextKey)
          } else {
            // Accessor or binds
            val metroFunction = metroFunctionOf(declaration, annotations)
            val contextKey = IrContextualTypeKey.from(this, declaration)
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
          val contextKey = IrContextualTypeKey.from(this, getter)
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

    scopes += buildSet {
      val scope =
        dependencyGraphAnno.getValueArgument(Symbols.Names.scope)?.let { scopeArg ->
          pluginContext.createIrBuilder(graphDeclaration.symbol).run {
            irCall(symbols.metroSingleInConstructor).apply { arguments[0] = scopeArg }
          }
        }

      if (scope != null) {
        add(IrAnnotation(scope))
        dependencyGraphAnno
          .getValueArgument(Symbols.Names.additionalScopes)
          ?.expectAs<IrVararg>()
          ?.elements
          ?.forEach { scopeArg ->
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
    }

    val scopesOnType = graphDeclaration.scopeAnnotations()
    scopes += scopesOnType
    for ((i, type) in supertypes.withIndex()) {
      val clazz = type.classOrFail.owner

      if (i != 0) {
        // We don't need to do a double lookup
        scopes += clazz.scopeAnnotations()
      }

      providerFactories += providesTransformer.factoryClassesFor(clazz)
    }

    if (isExtendable) {
      // Copy inherited scopes onto this graph for faster lookups downstream
      // Note this is only for scopes inherited from supertypes, not from extended parent graphs
      val inheritedScopes = (scopes - scopesOnType).map { it.ir }
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

    val creator =
      if (graphDeclaration.origin === Origins.ContributedGraph) {
        val ctor = graphDeclaration.primaryConstructor!!
        val ctorParams = ctor.parameters(metroContext)
        DependencyGraphNode.Creator.Constructor(graphDeclaration.primaryConstructor!!, ctorParams)
      } else {
        // TODO since we already check this in FIR can we leave a more specific breadcrumb somewhere
        graphDeclaration.nestedClasses
          .singleOrNull { klass ->
            klass.isAnnotatedWithAny(symbols.dependencyGraphFactoryAnnotations)
          }
          ?.let { factory ->
            // Validated in FIR so we can assume we'll find just one here
            val createFunction = factory.singleAbstractFunction(this)
            DependencyGraphNode.Creator.Factory(
              factory,
              createFunction,
              createFunction.parameters(this),
            )
          }
      }

    val includedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
    creator
      ?.parameters
      ?.regularParameters
      .orEmpty()
      .filter { it.isIncludes || it.isExtends }
      .forEach {
        val type = it.typeKey.type.rawType()
        val node =
          bindingStack.withEntry(
            IrBindingStack.Entry.requestedAt(graphContextKey, creator!!.function)
          ) {
            getOrComputeDependencyGraphNode(type, bindingStack, parentTracer)
          }
        if (it.isExtends) {
          extendedGraphNodes[it.typeKey] = node
        } else {
          // it.isIncludes
          includedGraphNodes[it.typeKey] = node
        }
      }

    val dependencyGraphNode =
      DependencyGraphNode(
        sourceGraph = graphDeclaration,
        supertypes = supertypes.toList(),
        isExtendable = isExtendable,
        includedGraphNodes = includedGraphNodes,
        contributedGraphs = contributedGraphs,
        scopes = scopes,
        bindsFunctions = bindsFunctions,
        providerFactories = providerFactories,
        accessors = accessors,
        injectors = injectors,
        isExternal = false,
        creator = creator,
        extendedGraphNodes = extendedGraphNodes,
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
      diagnosticReporter
        .at(graphDeclaration)
        .report(
          MetroIrErrors.METRO_ERROR,
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
}
