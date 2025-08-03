// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.transformers.InjectConstructorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.propertyIfAccessor

/**
 * Generates an [IrBindingGraph] for the given [node]. This only constructs the graph from available
 * bindings and does _not_ validate it.
 */
internal class BindingGraphGenerator(
  metroContext: IrMetroContext,
  private val node: DependencyGraphNode,
  // TODO preprocess these instead and just lookup via irAttribute
  private val injectConstructorTransformer: InjectConstructorTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val contributionData: IrContributionData,
) : IrMetroContext by metroContext {
  fun generate(): IrBindingGraph {
    val classBindingLookup =
      ClassBindingLookup(
        metroContext,
        node.sourceGraph,
        findClassFactory = { clazz ->
          injectConstructorTransformer.getOrGenerateFactory(
            clazz,
            previouslyFoundConstructor = null,
            doNotErrorOnMissing = true,
          )
        },
        findMemberInjectors = membersInjectorTransformer::getOrGenerateAllInjectorsFor,
      )

    val graph =
      IrBindingGraph(
        this,
        node,
        newBindingStack = {
          IrBindingStack(node.sourceGraph, loggerFor(MetroLogger.Type.BindingGraphConstruction))
        },
        classBindingLookup = classBindingLookup,
      )

    // Add explicit bindings from @Provides methods
    val bindingStack =
      IrBindingStack(
        node.sourceGraph,
        metroContext.loggerFor(MetroLogger.Type.BindingGraphConstruction),
      )

    // Add instance parameters
    val graphInstanceBinding =
      IrBinding.BoundInstance(node.typeKey, "${node.sourceGraph.name}Provider", node.sourceGraph)
    graph.addBinding(node.typeKey, graphInstanceBinding, bindingStack)

    // Mapping of supertypes to aliased bindings
    // We populate this for the current graph type first and then
    // add to them when processing extended parent graphs IFF there
    // is not already an existing entry. We do it this way to handle
    // cases where both the child graph and parent graph implement
    // a shared interface. In this scenario, the child alias wins
    // and we do not need to try to add another (duplicate) binding
    val superTypeToAlias = mutableMapOf<IrTypeKey, IrTypeKey>()

    // Add aliases for all its supertypes
    // TODO dedupe supertype iteration
    for (superType in node.supertypes) {
      val superTypeKey = IrTypeKey(superType)
      superTypeToAlias.putIfAbsent(superTypeKey, node.typeKey)
    }

    val providerFactoriesToAdd = buildList {
      addAll(node.providerFactories)
      addAll(
        node.allExtendedNodes.flatMap { (_, extendedNode) ->
          extendedNode.providerFactories.filterNot {
            // Do not include scoped providers as these should _only_ come from this graph
            // instance
            it.second.annotations.isScoped
          }
        }
      )
    }

    providerFactoriesToAdd.forEach { (typeKey, providerFactory) ->
      // Track a lookup of the provider class for IC
      trackClassLookup(node.sourceGraph, providerFactory.clazz)
      trackFunctionCall(node.sourceGraph, providerFactory.mirrorFunction)
      trackFunctionCall(node.sourceGraph, providerFactory.function)

      val contextKey =
        if (providerFactory.annotations.isIntoMultibinding) {
          IrContextualTypeKey.create(
            typeKey.transformMultiboundQualifier(providerFactory.annotations)
          )
        } else {
          IrContextualTypeKey.create(typeKey)
        }
      val provider =
        IrBinding.Provided(
          providerFactory = providerFactory,
          contextualTypeKey = contextKey,
          parameters = providerFactory.parameters,
          annotations = providerFactory.annotations,
        )

      if (provider.isIntoMultibinding) {
        val originalQualifier = providerFactory.function.qualifierAnnotation()
        graph
          .getOrCreateMultibinding(
            annotations = providerFactory.annotations,
            contextKey = contextKey,
            declaration = providerFactory.function,
            originalQualifier = originalQualifier,
            bindingStack = bindingStack,
          )
          .addSourceBinding(contextKey.typeKey)
      }

      graph.addBinding(contextKey.typeKey, provider, bindingStack)
    }

    // Add aliases ("@Binds")
    val bindsFunctionsToAdd = buildList {
      addAll(node.bindsCallables)
      // Exclude scoped Binds, those will be exposed via provider field accessor
      addAll(
        node.allExtendedNodes.values.filter { it.isExtendable }.flatMapToSet { it.bindsCallables }
      )
    }
    bindsFunctionsToAdd.forEach { bindingCallable ->
      val annotations = bindingCallable.callableMetadata.annotations
      val parameters = bindingCallable.function.parameters()
      val bindsImplType =
        if (annotations.isBinds) {
          parameters.extensionOrFirstParameter?.contextualTypeKey
            ?: error(
              "Missing receiver parameter for @Binds function: ${bindingCallable.function.dumpKotlinLike()} in class ${bindingCallable.function.parentAsClass.classId}"
            )
        } else {
          null
        }

      val targetTypeKey =
        if (annotations.isIntoMultibinding) {
          bindingCallable.target.transformMultiboundQualifier(annotations)
        } else {
          bindingCallable.target
        }

      val binding =
        IrBinding.Alias(
          targetTypeKey,
          bindsImplType?.typeKey
            ?: error(
              "Missing binds impl type for ${bindingCallable.function.name} in ${bindingCallable.function.parentAsClass.dumpKotlinLike()}"
            ),
          bindingCallable.function,
          parameters,
          annotations,
        )

      // Track a lookup of the target for IC
      trackClassLookup(node.sourceGraph, bindingCallable.function.parentAsClass)
      trackClassLookup(
        node.sourceGraph,
        bindingCallable.callableMetadata.mirrorFunction.parentAsClass,
      )
      trackFunctionCall(node.sourceGraph, bindingCallable.function)
      trackFunctionCall(node.sourceGraph, bindingCallable.callableMetadata.mirrorFunction)

      if (annotations.isIntoMultibinding) {
        graph
          .getOrCreateMultibinding(
            annotations = annotations,
            contextKey = IrContextualTypeKey.create(targetTypeKey),
            declaration = bindingCallable.function,
            originalQualifier = annotations.qualifier,
            bindingStack = bindingStack,
          )
          .addSourceBinding(binding.typeKey)
      }

      graph.addBinding(binding.typeKey, binding, bindingStack)
    }

    node.creator?.parameters?.regularParameters.orEmpty().forEach { creatorParam ->
      // Only expose the binding if it's a bound instance, extended graph, or target is annotated
      // @BindingContainer
      val shouldExposeBinding =
        creatorParam.isBindsInstance ||
          creatorParam.isExtends ||
          creatorParam.typeKey.type
            .rawTypeOrNull()
            ?.isAnnotatedWithAny(symbols.classIds.bindingContainerAnnotations) == true
      if (shouldExposeBinding) {
        val paramTypeKey = creatorParam.typeKey
        graph.addBinding(
          paramTypeKey,
          IrBinding.BoundInstance(creatorParam, creatorParam.ir),
          bindingStack,
        )
      }
    }

    node.bindingContainers.forEach {
      val typeKey = IrTypeKey(it)
      graph.addBinding(
        typeKey,
        IrBinding.BoundInstance(typeKey, it.name.asString(), it),
        bindingStack,
      )
    }

    fun addOrUpdateMultibinding(
      contextualTypeKey: IrContextualTypeKey,
      getter: IrSimpleFunction,
      multibinds: IrAnnotation,
    ) {
      if (contextualTypeKey.typeKey !in graph) {
        val multibinding =
          IrBinding.Multibinding.fromMultibindsDeclaration(getter, multibinds, contextualTypeKey)
        graph.addBinding(contextualTypeKey.typeKey, multibinding, bindingStack)
      } else {
        // If it's already in the graph, ensure its allowEmpty is up to date and update its
        // location
        graph
          .requireBinding(contextualTypeKey.typeKey, bindingStack)
          .expectAs<IrBinding.Multibinding>()
          .let {
            it.allowEmpty = multibinds.allowEmpty()
            it.declaration = getter
          }
      }

      // Record an IC lookup
      trackClassLookup(node.sourceGraph, getter.propertyIfAccessor.parentAsClass)
      trackFunctionCall(node.sourceGraph, getter)
    }

    node.multibindsCallables.forEach { multibindsCallable ->
      val contextKey = IrContextualTypeKey(multibindsCallable.typeKey)
      addOrUpdateMultibinding(
        contextKey,
        multibindsCallable.callableMetadata.mirrorFunction,
        multibindsCallable.callableMetadata.annotations.multibinds!!,
      )

      // Record an IC lookup of the original function/class for good measure
      trackFunctionCall(node.sourceGraph, multibindsCallable.function)
      trackClassLookup(
        node.sourceGraph,
        multibindsCallable.function.propertyIfAccessor.parentAsClass,
      )
    }

    // Traverse all parent graph supertypes to create binding aliases as needed
    node.allExtendedNodes.forEach { (typeKey, extendedNode) ->
      // If it's a contributed graph, add an alias for the parent types since that's what
      // bindings will look for. i.e. $$ContributedLoggedInGraph -> LoggedInGraph + supertypes
      for (superType in extendedNode.supertypes) {
        val parentTypeKey = IrTypeKey(superType)

        // Ignore the graph declaration itself, handled separately
        if (parentTypeKey == typeKey) continue

        superTypeToAlias.putIfAbsent(parentTypeKey, typeKey)
      }
    }

    // Now that we've processed all supertypes
    superTypeToAlias.forEach { (superTypeKey, aliasedType) ->
      // We may have already added a `@Binds` declaration explicitly, this is ok!
      // TODO warning?
      if (superTypeKey !in graph) {
        graph.addBinding(
          superTypeKey,
          IrBinding.Alias(
            superTypeKey,
            aliasedType,
            null,
            Parameters.empty(),
            MetroAnnotations.none(),
          ),
          bindingStack,
        )
      }
    }

    val accessorsToAdd = buildList {
      addAll(node.accessors)
      addAll(
        node.allExtendedNodes.flatMap { (_, extendedNode) ->
          // Pass down @Multibinds declarations in the same way we do for multibinding providers
          extendedNode.accessors.filter { it.first.annotations.isMultibinds }
        }
      )
    }

    accessorsToAdd.forEach { (getter, contextualTypeKey) ->
      val multibinds = getter.annotations.multibinds
      val isMultibindingDeclaration = multibinds != null

      if (isMultibindingDeclaration) {
        graph.addAccessor(
          contextualTypeKey,
          IrBindingStack.Entry.requestedAt(contextualTypeKey, getter.ir),
        )
        addOrUpdateMultibinding(contextualTypeKey, getter.ir, multibinds)
      } else {
        graph.addAccessor(
          contextualTypeKey,
          IrBindingStack.Entry.requestedAt(contextualTypeKey, getter.ir),
        )
      }
    }

    // Add bindings from graph dependencies
    // TODO dedupe this allDependencies iteration with graph gen
    // TODO try to make accessors in this single-pass
    // Only add it if it's a directly included node. Indirect will be propagated by metro
    // accessors
    node.includedGraphNodes.forEach { depNodeKey, depNode ->
      // Only add accessors for included types. If they're an accessor to a scoped provider, they
      // will be handled by the provider field accessor later
      for ((getter, contextualTypeKey) in depNode.accessors) {
        if (getter.annotations.isMetroAccessor) {
          // We'll handle this farther down
          continue
        }

        // Add a ref to the included graph if not already present
        if (depNodeKey !in graph) {
          graph.addBinding(
            depNodeKey,
            IrBinding.BoundInstance(
              depNodeKey,
              "${depNode.sourceGraph.name}Provider",
              depNode.sourceGraph,
            ),
            bindingStack,
          )
        }

        val irGetter = getter.ir
        val parentClass = irGetter.parentAsClass
        val parentName = parentClass.name
        val getterToUse =
          if (
            parentName == Symbols.Names.MetroGraph ||
              parentClass.origin == Origins.ContributedGraph ||
              parentName.asString().startsWith(Symbols.StringNames.CONTRIBUTED_GRAPH_PREFIX)
          ) {
            // Use the original graph decl so we don't tie this invocation to `$$MetroGraph`
            // specifically
            irGetter.overriddenSymbolsSequence().firstOrNull()?.owner
              ?: run {
                error(
                  "${irGetter.dumpKotlinLike()} overrides nothing, this is a bug in the Metro compiler"
                )
              }
          } else {
            irGetter
          }

        graph.addBinding(
          contextualTypeKey.typeKey,
          IrBinding.GraphDependency(
            ownerKey = depNodeKey,
            graph = depNode.sourceGraph,
            getter = getterToUse,
            isProviderFieldAccessor = false,
            typeKey = contextualTypeKey.typeKey,
          ),
          bindingStack,
        )
        // Record a lookup for IC
        trackFunctionCall(node.sourceGraph, irGetter)
        trackFunctionCall(node.sourceGraph, getterToUse)
      }
    }

    node.allExtendedNodes.values.forEach { depNode ->
      if (depNode.isExtendable) {
        val graphImpl = depNode.sourceGraph.metroGraphOrFail
        for (accessor in graphImpl.functions) {
          // Exclude toString/equals/hashCode or use marker annotation?
          if (accessor.isInheritedFromAny(pluginContext.irBuiltIns)) {
            continue
          }

          val annotations = accessor.metroAnnotations(symbols.classIds)
          if (!annotations.isMetroAccessor) continue

          val metroFunction = metroFunctionOf(accessor, annotations)
          val contextualTypeKey = IrContextualTypeKey.from(metroFunction.ir)
          val existingBinding = graph.findBinding(contextualTypeKey.typeKey)
          if (existingBinding != null) {
            // If it's a graph type we can just proceed, can happen with common ancestors
            val rawType = existingBinding.typeKey.type.rawTypeOrNull()
            if (rawType?.annotationsIn(symbols.dependencyGraphAnnotations).orEmpty().any()) {
              continue
            }
          }
          graph.addBinding(
            contextualTypeKey.typeKey,
            IrBinding.GraphDependency(
              ownerKey = depNode.typeKey,
              graph = depNode.sourceGraph,
              getter = metroFunction.ir,
              isProviderFieldAccessor = true,
              typeKey = contextualTypeKey.typeKey,
            ),
            bindingStack,
          )
          // Record a lookup for IC
          trackFunctionCall(node.sourceGraph, metroFunction.ir)
        }
      }
    }

    // Add MembersInjector bindings defined on injector functions
    node.injectors.forEach { (injector, contextKey) ->
      val entry = IrBindingStack.Entry.requestedAt(contextKey, injector.ir)

      graph.addInjector(contextKey, entry)
      bindingStack.withEntry(entry) {
        val param = injector.ir.regularParameters.single()
        val paramType = param.type
        val targetClass = paramType.rawType()
        // Don't return null on missing because it's legal to inject a class with no member
        // injections
        // TODO warn on this?
        val generatedInjector = membersInjectorTransformer.getOrGenerateInjector(targetClass)

        val remappedParams =
          if (targetClass.typeParameters.isEmpty()) {
              generatedInjector?.mergedParameters(NOOP_TYPE_REMAPPER)
            } else {
              // Create a remapper for the target class type parameters
              val remapper = targetClass.deepRemapperFor(paramType)
              val params = generatedInjector?.mergedParameters(remapper)
              params?.ir?.parameters(remapper) ?: params
            }
            .let { it ?: Parameters.empty() }
            .withCallableId(injector.callableId)

        val binding =
          IrBinding.MembersInjected(
            contextKey,
            // Need to look up the injector class and gather all params
            parameters = remappedParams,
            reportableDeclaration = injector.ir,
            function = injector.ir,
            isFromInjectorFunction = true,
            targetClassId = targetClass.classIdOrFail,
          )

        graph.addBinding(contextKey.typeKey, binding, bindingStack)

        // Ensure that we traverse the target class's superclasses and lookup relevant bindings for
        // them too, namely ancestor member injectors
        val extraBindings =
          classBindingLookup.lookup(
            IrContextualTypeKey.from(param),
            graph.bindingsSnapshot().keys,
            bindingStack,
          )
        for (extraBinding in extraBindings) {
          graph.addBinding(extraBinding.typeKey, extraBinding, bindingStack)
        }
      }
    }

    // Add bindings for scoped @Inject classes which don't have contributions
    if (node.isExtendable) {
      node.scopes.flatMap(contributionData::getScopedInjectClasses).forEach { scopedClassTypeKey ->
        if (scopedClassTypeKey !in graph) {
          val contextKey = IrContextualTypeKey.create(scopedClassTypeKey)
          val bindings =
            classBindingLookup.lookup(
              contextKey,
              graph.bindingsSnapshot().keys,
              IrBindingStack.empty(),
            )
          for (binding in bindings) {
            graph.addBinding(scopedClassTypeKey, binding, IrBindingStack.empty())
            // Mark this to be explicitly kept even after pruning unused
            graph.keep(scopedClassTypeKey)
          }
        }
      }
    }

    return graph
  }
}
