/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.ir.transformers

import dev.zacsweers.lattice.compiler.LatticeOrigin
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.NameAllocator
import dev.zacsweers.lattice.compiler.decapitalizeUS
import dev.zacsweers.lattice.compiler.exitProcessing
import dev.zacsweers.lattice.compiler.ir.Binding
import dev.zacsweers.lattice.compiler.ir.BindingGraph
import dev.zacsweers.lattice.compiler.ir.BindingStack
import dev.zacsweers.lattice.compiler.ir.ContextualTypeKey
import dev.zacsweers.lattice.compiler.ir.DependencyGraphNode
import dev.zacsweers.lattice.compiler.ir.IrAnnotation
import dev.zacsweers.lattice.compiler.ir.LatticeSimpleFunction
import dev.zacsweers.lattice.compiler.ir.LatticeTransformerContext
import dev.zacsweers.lattice.compiler.ir.TypeKey
import dev.zacsweers.lattice.compiler.ir.addCompanionObject
import dev.zacsweers.lattice.compiler.ir.addOverride
import dev.zacsweers.lattice.compiler.ir.allCallableMembers
import dev.zacsweers.lattice.compiler.ir.appendBindingStack
import dev.zacsweers.lattice.compiler.ir.asContextualTypeKey
import dev.zacsweers.lattice.compiler.ir.buildBlockBody
import dev.zacsweers.lattice.compiler.ir.createIrBuilder
import dev.zacsweers.lattice.compiler.ir.declaredCallableMembers
import dev.zacsweers.lattice.compiler.ir.doubleCheck
import dev.zacsweers.lattice.compiler.ir.getAllSuperTypes
import dev.zacsweers.lattice.compiler.ir.getSingleConstBooleanArgumentOrNull
import dev.zacsweers.lattice.compiler.ir.irBlockBody
import dev.zacsweers.lattice.compiler.ir.irInvoke
import dev.zacsweers.lattice.compiler.ir.irLambda
import dev.zacsweers.lattice.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.ir.isExternalParent
import dev.zacsweers.lattice.compiler.ir.location
import dev.zacsweers.lattice.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameters
import dev.zacsweers.lattice.compiler.ir.parameters.parameters
import dev.zacsweers.lattice.compiler.ir.parameters.wrapInLazy
import dev.zacsweers.lattice.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.lattice.compiler.ir.rawType
import dev.zacsweers.lattice.compiler.ir.rawTypeOrNull
import dev.zacsweers.lattice.compiler.ir.singleAbstractFunction
import dev.zacsweers.lattice.compiler.ir.thisReceiverOrFail
import dev.zacsweers.lattice.compiler.ir.typeAsProviderArgument
import dev.zacsweers.lattice.compiler.ir.withEntry
import dev.zacsweers.lattice.compiler.letIf
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.setDeclarationsParent
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class DependencyGraphData {
  val graphs = mutableMapOf<ClassId, DependencyGraphNode>()
}

internal class DependencyGraphTransformer(context: LatticeTransformerContext) :
  IrElementTransformer<DependencyGraphData>, LatticeTransformerContext by context {

  private val membersInjectorTransformer = MembersInjectorTransformer(context)
  private val injectConstructorTransformer =
    InjectConstructorTransformer(context, membersInjectorTransformer)
  private val assistedFactoryTransformer =
    AssistedFactoryTransformer(context, injectConstructorTransformer)
  private val providesTransformer = ProvidesTransformer(context)

  // Keyed by the source declaration
  private val dependencyGraphNodesByClass = mutableMapOf<ClassId, DependencyGraphNode>()
  // Keyed by the source declaration
  private val latticeDependencyGraphsByClass = mutableMapOf<ClassId, IrClass>()

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  override fun visitCall(expression: IrCall, data: DependencyGraphData): IrElement {
    // Covers replacing createGraphFactory() compiler intrinsics with calls to the real
    // graph factory
    val callee = expression.symbol.owner
    when (callee.symbol) {
      symbols.latticeCreateGraphFactory -> {
        // Get the called type
        val type =
          expression.getTypeArgument(0)
            ?: error("Missing type argument for ${symbols.latticeCreateGraphFactory.owner.name}")
        val rawType = type.rawType()
        if (!rawType.isAnnotatedWithAny(symbols.dependencyGraphFactoryAnnotations)) {
          // TODO FIR error
          error(
            "Cannot create a graph factory instance of non-factory type ${rawType.kotlinFqName}"
          )
        }
        val parentDeclaration = rawType.parentAsClass
        val graphClass = getOrBuildDependencyGraph(parentDeclaration)
        val graphCompanion = graphClass.companionObject()!!
        val factoryFunction = graphCompanion.getSimpleFunction("factory")!!
        // Replace it with a call directly to the factory function
        return pluginContext.createIrBuilder(expression.symbol).run {
          irCall(callee = factoryFunction, type = type).apply {
            dispatchReceiver = irGetObject(graphCompanion.symbol)
          }
        }
      }
      symbols.latticeCreateGraph -> {
        // Get the called type
        val type =
          expression.getTypeArgument(0)
            ?: error("Missing type argument for ${symbols.latticeCreateGraph.owner.name}")
        val rawType = type.rawType()
        if (!rawType.isAnnotatedWithAny(symbols.dependencyGraphAnnotations)) {
          // TODO FIR error
          error(
            "Cannot create an dependency graph instance of non-graph type ${rawType.kotlinFqName}"
          )
        }
        val graphClass = getOrBuildDependencyGraph(rawType)
        val graphCompanion = graphClass.companionObject()!!
        val factoryFunction = graphCompanion.getSimpleFunction(LatticeSymbols.StringNames.Create)!!
        // Replace it with a call directly to the create function
        return pluginContext.createIrBuilder(expression.symbol).run {
          irCall(callee = factoryFunction, type = type).apply {
            dispatchReceiver = irGetObject(graphCompanion.symbol)
          }
        }
      }
    }

    return super.visitCall(expression, data)
  }

  override fun visitClass(declaration: IrClass, data: DependencyGraphData): IrStatement {
    log("Reading <$declaration>")

    // TODO need to better divvy these
    membersInjectorTransformer.visitClass(declaration)
    injectConstructorTransformer.visitClass(declaration)
    assistedFactoryTransformer.visitClass(declaration)
    providesTransformer.visitClass(declaration)

    val isDependencyGraph = declaration.isAnnotatedWithAny(symbols.dependencyGraphAnnotations)
    if (!isDependencyGraph) return super.visitClass(declaration, data)

    getOrBuildDependencyGraph(declaration)

    // TODO dump option to detect unused

    return super.visitClass(declaration, data)
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun getOrComputeDependencyGraphNode(
    graphDeclaration: IrClass,
    bindingStack: BindingStack,
    generatedGraphId: ClassId =
      graphDeclaration.classIdOrFail.createNestedClassId(LatticeSymbols.Names.LatticeGraph),
  ): DependencyGraphNode {
    val graphClassId = graphDeclaration.classIdOrFail
    dependencyGraphNodesByClass[graphClassId]?.let {
      return it
    }

    val graphTypeKey = TypeKey(graphDeclaration.typeWith())
    val graphContextKey = ContextualTypeKey(graphTypeKey, false, false, false, false)
    if (bindingStack.entryFor(graphTypeKey) != null) {
      // TODO dagger doesn't appear to error for this case to model off of
      val message = buildString {
        if (bindingStack.entries.size == 1) {
          // If there's just one entry, specify that it's a self-referencing cycle for clarity
          appendLine(
            "[Lattice/GraphDependencyCycle] Graph dependency cycle detected! The below graph depends on itself."
          )
        } else {
          appendLine("[Lattice/GraphDependencyCycle] Graph dependency cycle detected!")
        }
        appendBindingStack(bindingStack, short = false)
      }
      graphDeclaration.reportError(message)
      exitProcessing()
    }

    graphDeclaration.constructors.forEach { constructor ->
      if (constructor.valueParameters.isNotEmpty()) {
        // TODO dagger doesn't appear to error for this case to model off of
        constructor.reportError(
          "Dependency graphs cannot have constructors. Use @DependencyGraph.Factory instead."
        )
        exitProcessing()
      }
    }

    val scopes = mutableSetOf<IrAnnotation>()
    val providerFunctions = mutableListOf<Pair<TypeKey, LatticeSimpleFunction>>()

    for (type in graphDeclaration.getAllSuperTypes(pluginContext, excludeSelf = false)) {
      val clazz = type.classOrFail.owner
      scopes += clazz.scopeAnnotations()

      // TODO is this enough for properties like @get:Provides
      providerFunctions +=
        clazz
          .allCallableMembers(
            latticeContext,
            functionFilter = { function ->
              // Skip fake overrides. These are types that are inherited from a supertype but
              // not actually user-implemented in the subtype
              !function.isFakeOverride &&
                function.correspondingPropertySymbol?.owner?.isFakeOverride != true
            },
          )
          .filter { function -> function.annotations.run { isProvides || isBinds } }
          .map { function ->
            ContextualTypeKey.from(this, function.ir).typeKey to function
          }
    }

    val exposedTypes = mutableMapOf<LatticeSimpleFunction, ContextualTypeKey>()
    val injectors = mutableMapOf<LatticeSimpleFunction, ContextualTypeKey>()

    for (function in graphDeclaration.allCallableMembers(latticeContext)) {
      // Abstract check is important. We leave alone any non-providers/injectors
      val isCandidate =
        function.ir.modality == Modality.ABSTRACT &&
          function.ir.body == null &&
          !function.annotations.isBinds &&
          !function.annotations.isProvides
      if (isCandidate) {
        if (function.ir.valueParameters.isEmpty()) {
          val contextKey = ContextualTypeKey.from(this, function.ir)
          exposedTypes[function] = contextKey
        } else {
          // TODO FIR check only one param, no intrinsics, no return type
          val target = function.ir.parameters(this).valueParameters.single()
          injectors[function] = target.contextualTypeKey
        }
      }
    }

    val creator =
      graphDeclaration.nestedClasses
        .singleOrNull { klass ->
          klass.isAnnotatedWithAny(symbols.dependencyGraphFactoryAnnotations)
        }
        ?.let { factory ->
          // Validated in FIR so we can assume we'll find just one here
          // TODO support properties? Would be odd but technically possible
          val createFunction = factory.singleAbstractFunction(this)
          DependencyGraphNode.Creator(factory, createFunction, createFunction.parameters(this))
        }

    val graphDependencies =
      creator
        ?.parameters
        ?.valueParameters
        .orEmpty()
        .filter { !it.isBindsInstance }
        .map {
          val type = it.typeKey.type.rawType()
          bindingStack.withEntry(
            BindingStack.Entry.requestedAt(graphContextKey, creator!!.createFunction)
          ) {
            getOrComputeDependencyGraphNode(type, bindingStack)
          }
        }

    val dependencyGraphNode =
      DependencyGraphNode(
        sourceGraph = graphDeclaration,
        generatedGraphId = generatedGraphId,
        isAnnotatedWithDependencyGraph = true,
        dependencies = graphDependencies,
        scopes = scopes,
        providerFunctions = providerFunctions,
        exposedTypes = exposedTypes,
        injectors = injectors,
        isExternal = false,
        creator = creator,
        typeKey = graphTypeKey,
      )
    dependencyGraphNodesByClass[graphClassId] = dependencyGraphNode
    return dependencyGraphNode
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun getOrBuildDependencyGraph(dependencyGraphDeclaration: IrClass): IrClass {
    val graphClassId = dependencyGraphDeclaration.classIdOrFail
    latticeDependencyGraphsByClass[graphClassId]?.let {
      return it
    }

    if (dependencyGraphDeclaration.isExternalParent) {
      // Externally compiled, look up its generated class
      // TODO won't be visible until we add metadata to generated classes
      val generatedGraph =
        dependencyGraphDeclaration.nestedClasses.singleOrNull {
          it.name == LatticeSymbols.Names.LatticeGraph
        }
      if (generatedGraph == null) {
        error("Expected generated dependency graph for $graphClassId")
      } else {
        latticeDependencyGraphsByClass[graphClassId] = generatedGraph
        return generatedGraph
      }
    }

    val node =
      getOrComputeDependencyGraphNode(
        dependencyGraphDeclaration,
        BindingStack(dependencyGraphDeclaration),
      )

    val bindingGraph = createBindingGraph(node)
    val deferredTypes =
      bindingGraph.validate(node) { message ->
        dependencyGraphDeclaration.reportError(message)
        exitProcessing()
      }

    val latticeGraph = generateLatticeGraph(node, bindingGraph, deferredTypes)

    // TODO consolidate logic
    latticeGraph.dumpToLatticeLog()
    dependencyGraphDeclaration.addChild(latticeGraph)
    latticeDependencyGraphsByClass[graphClassId] = latticeGraph
    return latticeGraph
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun createBindingGraph(node: DependencyGraphNode): BindingGraph {
    val graph = BindingGraph(this)

    // Add explicit bindings from @Provides methods
    val bindingStack = BindingStack(node.sourceGraph)
    node.providerFunctions.forEach { (typeKey, function) ->
      val annotations = function.annotations
      val parameters = function.ir.parameters(latticeContext)

      val bindsImplType =
        if (annotations.isBinds) {
          parameters.extensionReceiver!!.contextualTypeKey
        } else {
          null
        }

      val provider =
        Binding.Provided(
          providerFunction = function.ir,
          contextualTypeKey = ContextualTypeKey.from(latticeContext, function.ir),
          parameters = parameters,
          // TODO FIR only one annotation is allowed
          // TODO FIR no scopes on multibindings
          // TODO FIR can't mix @Multibinds and @Provides
          annotations = annotations,
          aliasedType = bindsImplType,
        )

      if (provider.isMultibindingProvider) {
        val multibindingType =
          when {
            provider.intoSet -> {
              pluginContext.irBuiltIns.setClass.typeWith(provider.typeKey.type)
            }
            // TODO Dagger only supports the target collection, but maybe we can loosen that?
            provider.elementsIntoSet -> provider.typeKey.type
            provider.intoMap && provider.mapKey != null -> {
              // TODO this is probably not robust enough
              val rawKeyType = provider.mapKey.ir
              val unwrapValues = rawKeyType.shouldUnwrapMapKeyValues()
              val keyType =
                if (unwrapValues) {
                  rawKeyType.annotationClass.primaryConstructor!!.valueParameters[0].type
                } else {
                  rawKeyType.type
                }
              pluginContext.irBuiltIns.mapClass.typeWith(
                // MapKey is the key type
                keyType,
                // Return type is the value type
                provider.typeKey.type,
              )
            }
            else -> error("Not possible")
          }
        val multibindingTypeKey = provider.typeKey.copy(type = multibindingType)
        graph.getOrCreateMultibinding(pluginContext, multibindingTypeKey).providers.add(provider)
      } else {
        graph.addBinding(typeKey, provider, bindingStack)
      }
    }

    // Add instance parameters
    graph.addBinding(
      node.typeKey,
      Binding.BoundInstance(
        node.typeKey,
        "${node.sourceGraph.name}Provider",
        node.sourceGraph.location(),
      ),
      bindingStack,
    )
    node.creator?.parameters?.valueParameters.orEmpty().forEach {
      graph.addBinding(it.typeKey, Binding.BoundInstance(it), bindingStack)
    }

    node.exposedTypes.forEach { getter, contextualTypeKey ->
      val isMultibindingDeclaration = getter.annotations.isMultibinds

      if (isMultibindingDeclaration) {
        graph.addBinding(
          contextualTypeKey.typeKey,
          Binding.Multibinding.create(pluginContext, contextualTypeKey.typeKey),
          bindingStack,
        )
      } else {
        graph.addExposedType(
          contextualTypeKey,
          BindingStack.Entry.requestedAt(contextualTypeKey, getter.ir),
        )
      }
    }

    // Add bindings from graph dependencies
    node.dependencies.forEach { depNode ->
      depNode.exposedTypes.forEach { (getter, contextualTypeKey) ->
        graph.addBinding(
          contextualTypeKey.typeKey,
          Binding.GraphDependency(
            graph = depNode.sourceGraph,
            getter = getter.ir,
            typeKey = contextualTypeKey.typeKey,
          ),
          bindingStack,
        )
      }
    }

    // Add MembersInjector bindings defined on injector functions
    // I was half asleep when I wrote the below and some day I will translate what I was trying to
    // say
    // This will add bindings as provider fields for each type. We do need this, but we don't want
    // that for
    // these. Maybe we add a property to indicate if it's from an inject() function and don't add to
    // provider fields
    // for those cases
    node.injectors.forEach { (injector, contextualTypeKey) ->
      val entry = BindingStack.Entry.requestedAt(contextualTypeKey, injector.ir)

      bindingStack.withEntry(entry) {
        val targetClass = injector.ir.valueParameters.single().type.rawType()
        val generatedInjector = membersInjectorTransformer.getOrGenerateInjector(targetClass)
        val allParams = generatedInjector?.injectFunctions?.values?.toList().orEmpty()
        val parameters =
          when (allParams.size) {
            0 -> {
              Parameters.empty()
            }
            1 -> {
              allParams.first()
            }
            else -> {
              allParams.reduce { current, next -> current.mergeValueParametersWith(next) }
            }
          }
        val membersInjectorKey =
          ContextualTypeKey(
            typeKey =
              TypeKey(symbols.latticeMembersInjector.typeWith(contextualTypeKey.typeKey.type)),
            isWrappedInProvider = false,
            isWrappedInLazy = false,
            isLazyWrappedInProvider = false,
            hasDefault = false,
          )
        val binding =
          Binding.MembersInjected(
            membersInjectorKey,
            // Need to look up the injector class and gather all params
            parameters =
              Parameters(injector.callableId, null, null, parameters.valueParameters, null),
            reportableLocation = injector.ir.location(),
            function = injector.ir,
            isFromInjectorFunction = true,
            targetClassId = targetClass.classIdOrFail,
          )
        graph.addBinding(membersInjectorKey.typeKey, binding, bindingStack)
      }
    }

    // Don't eagerly create bindings for injectable types, they'll be created on-demand
    // when dependencies are analyzed
    // TODO collect unused bindings?

    return graph
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun generateLatticeGraph(
    node: DependencyGraphNode,
    graph: BindingGraph,
    deferredTypes: Set<TypeKey>,
  ): IrClass {
    /*
    Simple object that exposes a factory function

    public static ExampleGraph.Factory factory() {
      return new Factory();
    }

    public static ExampleGraph create() {
      return new Factory().create();
    }

    private static final class Factory implements ExampleGraph.Factory {
      @Override
      public ExampleGraph create() {
        return new ExampleGraphImpl();
      }
    }
    */
    return pluginContext.irFactory
      .buildClass {
        name = node.generatedGraphId.shortClassName
        kind = ClassKind.OBJECT
        origin = LatticeOrigin
      }
      .apply {
        createImplicitParameterDeclarationWithWrappedDescriptor()
        addSimpleDelegatingConstructor(
          symbols.anyConstructor,
          pluginContext.irBuiltIns,
          isPrimary = true,
          origin = LatticeOrigin,
        )

        val graphImpl = generateGraphImpl(node, graph, deferredTypes)
        addChild(graphImpl)

        val creator = node.creator
        if (creator != null) {
          val factoryClass =
            pluginContext.irFactory
              .buildClass { name = LatticeSymbols.Names.Factory }
              .apply {
                this.origin = LatticeOrigin
                superTypes += node.creator.type.symbol.typeWith()
                createImplicitParameterDeclarationWithWrappedDescriptor()
                addSimpleDelegatingConstructor(
                  if (!node.creator.type.isInterface) {
                    node.creator.type.primaryConstructor!!
                  } else {
                    symbols.anyConstructor
                  },
                  pluginContext.irBuiltIns,
                  isPrimary = true,
                  origin = LatticeOrigin,
                )

                addOverride(node.creator.createFunction).apply {
                  body =
                    pluginContext.createIrBuilder(symbol).run {
                      irBlockBody(
                        symbol,
                        irCall(graphImpl.primaryConstructor!!.symbol).apply {
                          for (param in valueParameters) {
                            putValueArgument(param.index, irGet(param))
                          }
                        },
                      )
                    }
                }
              }

          addChild(factoryClass)

          pluginContext.irFactory.addCompanionObject(symbols, parent = this) {
            addFunction("factory", factoryClass.typeWith()).apply {
              this.copyTypeParameters(typeParameters)
              this.dispatchReceiverParameter = thisReceiver?.copyTo(this)
              this.origin = LatticeOrigin
              this.visibility = DescriptorVisibilities.PUBLIC
              body =
                pluginContext.createIrBuilder(symbol).run {
                  irBlockBody(
                    symbol,
                    irCallConstructor(factoryClass.primaryConstructor!!.symbol, emptyList()),
                  )
                }
            }
          }
        } else {
          // Generate a no-arg create() function
          pluginContext.irFactory.addCompanionObject(symbols, parent = this) {
            addFunction(LatticeSymbols.StringNames.Create, node.sourceGraph.typeWith()).apply {
              this.origin = LatticeOrigin
              this.visibility = DescriptorVisibilities.PUBLIC
              body =
                pluginContext.createIrBuilder(symbol).run {
                  irBlockBody(
                    symbol,
                    irCallConstructor(graphImpl.primaryConstructor!!.symbol, emptyList()),
                  )
                }
            }
          }
        }
      }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun generateGraphImpl(
    node: DependencyGraphNode,
    bindingGraph: BindingGraph,
    deferredTypes: Set<TypeKey>,
  ): IrClass {
    val graphImplName = "${node.sourceGraph.name.asString()}Impl"
    return pluginContext.irFactory
      .buildClass { name = Name.identifier(graphImplName) }
      .apply {
        superTypes += node.sourceGraph.typeWith()
        origin = LatticeOrigin

        val graphImpl = this

        createImplicitParameterDeclarationWithWrappedDescriptor()
        val ctor =
          addSimpleDelegatingConstructor(
            node.sourceGraph.primaryConstructor ?: symbols.anyConstructor,
            pluginContext.irBuiltIns,
            isPrimary = true,
            origin = LatticeOrigin,
          )

        // Add fields for providers. May include both scoped and unscoped providers as well as bound
        // instances
        val providerFields = mutableMapOf<TypeKey, IrField>()
        val multibindingProviderFields = mutableMapOf<Binding.Provided, IrField>()
        val graphTypesToCtorParams = mutableMapOf<TypeKey, IrValueParameter>()
        val fieldNameAllocator = NameAllocator()

        node.creator?.let { creator ->
          for (param in creator.parameters.valueParameters) {
            val isBindsInstance = param.isBindsInstance

            val irParam = ctor.addValueParameter(param.name.asString(), param.type)

            if (isBindsInstance) {
              providerFields[param.typeKey] =
                addField(
                    fieldName = fieldNameAllocator.newName("${param.name}Instance"),
                    fieldType = symbols.latticeProvider.typeWith(param.type),
                    fieldVisibility = DescriptorVisibilities.PRIVATE,
                  )
                  .apply {
                    isFinal = true
                    initializer =
                      pluginContext.createIrBuilder(symbol).run {
                        // InstanceFactory.create(...)
                        // TODO consolidate with membersinjector
                        irExprBody(
                          irInvoke(
                              dispatchReceiver =
                                irGetObject(symbols.instanceFactoryCompanionObject),
                              callee = symbols.instanceFactoryCreate,
                              args = listOf(irGet(irParam)),
                              typeHint = param.type.wrapInProvider(symbols.latticeFactory),
                            )
                            .apply { putTypeArgument(0, param.type) }
                        )
                      }
                  }
            } else {
              // It's a graph dep. Add all its exposed types as available keys and point them at
              // this constructor parameter for provider field initialization
              for (graphDep in node.allDependencies) {
                for ((_, contextualTypeKey) in graphDep.exposedTypes) {
                  graphTypesToCtorParams[contextualTypeKey.typeKey] = irParam
                }
              }
            }
          }
        }

        // Add fields for this graph and other instance params
        val instanceFields = mutableMapOf<TypeKey, IrField>()
        val thisReceiverParameter = thisReceiverOrFail
        val thisGraphField =
          addField(
              fieldName = fieldNameAllocator.newName(graphImplName.decapitalizeUS()),
              fieldType = thisReceiverParameter.type,
              fieldVisibility = DescriptorVisibilities.PRIVATE,
            )
            .apply {
              isFinal = true
              initializer =
                pluginContext.createIrBuilder(symbol).run {
                  irExprBody(irGet(thisReceiverParameter))
                }
            }

        instanceFields[node.typeKey] = thisGraphField
        // Add convenience mappings for all supertypes to this field so
        // instance providers from inherited types use this instance
        for (superType in node.sourceGraph.getAllSuperTypes(pluginContext)) {
          instanceFields[TypeKey(superType)] = thisGraphField
        }

        // Expose the graph as a provider binding
        providerFields[node.typeKey] =
          addField(
              fieldName = fieldNameAllocator.newName("${node.sourceGraph.name.asString().decapitalizeUS()}Provider"),
              fieldType = symbols.latticeProvider.typeWith(node.typeKey.type),
              fieldVisibility = DescriptorVisibilities.PRIVATE,
            )
            .apply {
              isFinal = true
              initializer =
                pluginContext.createIrBuilder(symbol).run {
                  // InstanceFactory.create(...)
                  irExprBody(
                    irInvoke(
                        dispatchReceiver = irGetObject(symbols.instanceFactoryCompanionObject),
                        callee = symbols.instanceFactoryCreate,
                        args = listOf(irGetField(irGet(thisReceiverParameter), thisGraphField)),
                        typeHint = node.typeKey.type.wrapInProvider(symbols.latticeFactory),
                      )
                      .apply { putTypeArgument(0, node.typeKey.type) }
                  )
                }
            }

        // Track a stack for bindings
        val bindingStack = BindingStack(node.sourceGraph)

        // First pass: collect bindings and their dependencies for provider field ordering
        // Note we do this in two passes rather than keep a TreeMap because otherwise we'd be doing
        // dependency lookups at each insertion
        val bindingDependencies = collectBindings(node, bindingGraph, bindingStack)

        // Compute safe initialization order
        val initOrder =
          bindingDependencies.keys
            .sortedWith { a, b ->
              when {
                // If b depends on a, a should be initialized first
                a in (bindingDependencies[b]?.dependencies.orEmpty()) -> -1
                // If a depends on b, b should be initialized first
                b in (bindingDependencies[a]?.dependencies.orEmpty()) -> 1
                // Otherwise order doesn't matter, fall back to just type order for idempotence
                else -> a.compareTo(b)
              }
            }
            .map { bindingDependencies.getValue(it) }
            .distinct()

        val generationContext =
          GraphGenerationContext(
            bindingGraph,
            thisReceiverParameter,
            instanceFields,
            graphTypesToCtorParams,
            providerFields,
            multibindingProviderFields,
            bindingStack,
          )

        // For all deferred types, assign them first as factories
        // TODO For any types that depend on deferred types, they need providers too?
        @Suppress("UNCHECKED_CAST")
        val deferredFields: Map<TypeKey, IrField> =
          deferredTypes
            .associateWith { deferredTypeKey ->
              val binding = bindingDependencies[deferredTypeKey] ?: return@associateWith null
              val field =
                addField(
                    fieldNameAllocator.newName(binding.nameHint.decapitalizeUS() + "Provider"),
                    deferredTypeKey.type.wrapInProvider(symbols.latticeProvider),
                  )
                  .apply {
                    initializer =
                      pluginContext.createIrBuilder(symbol).run {
                        irExprBody(
                          irInvoke(callee = symbols.latticeDelegateFactoryConstructor).apply {
                            putTypeArgument(0, deferredTypeKey.type)
                          }
                        )
                      }
                  }

              providerFields[deferredTypeKey] = field

              with(ctor) {
                val originalBody = checkNotNull(body)
                buildBlockBody(pluginContext) {
                  +originalBody.statements
                  +irInvoke(
                    dispatchReceiver = irGetObject(symbols.latticeDelegateFactoryCompanion),
                    callee = symbols.latticeDelegateFactorySetDelegate,
                    // TODO de-dupe?
                    args =
                      listOf(
                        irGetField(irGet(thisReceiverParameter), field),
                        pluginContext.createIrBuilder(symbol).run {
                          generateBindingCode(
                              binding,
                              generationContext,
                              fieldInitKey = deferredTypeKey,
                            )
                            .letIf(binding.scope != null) {
                              // If it's scoped, wrap it in double-check
                              // DoubleCheck.provider(<provider>)
                              it.doubleCheck(this@run, symbols)
                            }
                        },
                      ),
                  )
                }
              }

              field
            }
            .filterValues { it != null } as Map<TypeKey, IrField>

        // Create fields in dependency-order
        initOrder
          .filterNot { it.typeKey in deferredFields }
          .forEach { binding ->
            val key = binding.typeKey
            // Since assisted injections don't implement Factory, we can't just type these as
            // Provider<*> fields
            val fieldType =
              if (binding is Binding.ConstructorInjected && binding.isAssisted) {
                injectConstructorTransformer
                  .getOrGenerateFactoryClass(binding.type, binding.injectedConstructor)
                  .typeWith() // TODO generic factories?
              } else {
                symbols.latticeProvider.typeWith(key.type)
              }

            val field =
              addField(
                  fieldName =
                    fieldNameAllocator.newName(binding.nameHint.decapitalizeUS() + "Provider"),
                  fieldType = fieldType,
                  fieldVisibility = DescriptorVisibilities.PRIVATE,
                )
                .apply {
                  isFinal = true
                  initializer =
                    pluginContext.createIrBuilder(symbol).run {
                      val provider =
                        generateBindingCode(binding, generationContext).letIf(
                          binding.scope != null
                        ) {
                          // If it's scoped, wrap it in double-check
                          // DoubleCheck.provider(<provider>)
                          it.doubleCheck(this@run, symbols)
                        }
                      irExprBody(provider)
                    }
                }
            if (binding is Binding.Provided && binding.isMultibindingProvider) {
              multibindingProviderFields[binding] = field
            } else {
              providerFields[key] = field
            }
          }

        // Implement abstract getters for exposed types
        node.exposedTypes.entries
          // Stable sort. First the name then the type
          .sortedWith(
            compareBy<Map.Entry<LatticeSimpleFunction, ContextualTypeKey>> { it.key.ir.name }
              .thenComparing { it.value.typeKey }
          )
          .forEach { (function, contextualTypeKey) ->
            val property =
              function.ir.correspondingPropertySymbol?.owner?.let { property ->
                addProperty { name = property.name }.apply { setDeclarationsParent(graphImpl) }
              }
            val getter =
              property
                ?.addGetter { returnType = function.ir.returnType }
                ?.apply {
                  this.overriddenSymbols += function.ir.symbol
                  this.correspondingPropertySymbol = property.symbol
                }
                ?: addOverride(
                  function.ir.kotlinFqName,
                  function.ir.name,
                  function.ir.returnType,
                  overriddenSymbols = listOf(function.ir.symbol),
                )
            getter.apply {
              this.dispatchReceiverParameter = thisReceiverParameter
              val binding = bindingGraph.getOrCreateBinding(contextualTypeKey, BindingStack.empty())
              bindingStack.push(BindingStack.Entry.requestedAt(contextualTypeKey, function.ir))
              body =
                pluginContext.createIrBuilder(symbol).run {
                  if (binding is Binding.Multibinding) {
                    // TODO if we have multiple exposed types pointing at the same type, implement
                    //  one and make the rest call that one. Not multibinding specific. Maybe
                    //  groupBy { typekey }?
                  }
                  irBlockBody(
                    symbol,
                    typeAsProviderArgument(
                      latticeContext,
                      contextualTypeKey,
                      generateBindingCode(binding, generationContext, contextualTypeKey),
                      isAssisted = false,
                      isGraphInstance = false,
                    ),
                  )
                }
            }
            bindingStack.pop()
          }

        // Implement abstract injectors
        node.injectors.entries
          // Stable sort. First the name then the type
          .sortedWith(
            compareBy<Map.Entry<LatticeSimpleFunction, ContextualTypeKey>> { it.key.ir.name }
              .thenComparing { it.value.typeKey }
          )
          .forEach { (function, contextualTypeKey) ->
            val overriddenFunction =
              addOverride(
                function.ir.kotlinFqName,
                function.ir.name,
                function.ir.returnType,
                overriddenSymbols = listOf(function.ir.symbol),
              )
            overriddenFunction.apply {
              this.dispatchReceiverParameter = thisReceiverParameter
              setDeclarationsParent(graphImpl)
              val targetParam = addValueParameter("target", contextualTypeKey.typeKey.type)
              val membersInjectorKey =
                TypeKey(symbols.latticeMembersInjector.typeWith(contextualTypeKey.typeKey.type))
              val binding =
                bindingGraph.requireBinding(membersInjectorKey) as Binding.MembersInjected
              bindingStack.push(BindingStack.Entry.requestedAt(contextualTypeKey, function.ir))

              // We don't get a MembersInjector instance/provider from the graph. Instead, we call
              // all the target inject functions directly
              body =
                pluginContext.createIrBuilder(symbol).irBlockBody {
                  // TODO reuse, consolidate calling code with how we implement this in
                  //  constructor inject code gen
                  // val injectors =
                  // membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)
                  // val memberInjectParameters = injectors.flatMap { it.parameters.values.flatten()
                  // }

                  for (type in
                    pluginContext
                      .referenceClass(binding.targetClassId)!!
                      .owner
                      .getAllSuperTypes(pluginContext, excludeSelf = false, excludeAny = true)) {
                    val clazz = type.rawType()
                    val injectors =
                      membersInjectorTransformer.getOrGenerateInjector(clazz) ?: continue
                    for ((function, parameters) in injectors.injectFunctions) {
                      +irInvoke(
                        dispatchReceiver = irGetObject(function.parentAsClass.symbol),
                        callee = function.symbol,
                        args =
                          buildList {
                            add(irGet(targetParam))
                            for (parameter in parameters.valueParameters) {
                              val paramBinding =
                                bindingGraph.getOrCreateBinding(
                                  parameter.contextualTypeKey,
                                  bindingStack,
                                )
                              add(
                                typeAsProviderArgument(
                                  latticeContext,
                                  parameter.contextualTypeKey,
                                  generateBindingCode(
                                    paramBinding,
                                    generationContext,
                                    parameter.contextualTypeKey,
                                  ),
                                  isAssisted = false,
                                  isGraphInstance = false,
                                )
                              )
                            }
                          },
                      )
                    }
                  }
                }
            }
            bindingStack.pop()
          }

        // Implement no-op bodies for Binds providers
        node.providerFunctions
          .filter { it.second.annotations.isBinds }
          .filter { (key, _) ->
            val binding = bindingGraph.requireBinding(key) as Binding.Provided
            binding.aliasedType != null
          }
          // Stable sort. First the name then the type
          .sortedWith(
            compareBy<Pair<TypeKey, LatticeSimpleFunction>> { it.second.ir.name }
              .thenComparing { it.first }
          )
          .forEach { (_, function) ->
            // TODO dedupe with accessors gen
            val property =
              function.ir.correspondingPropertySymbol?.owner?.let { property ->
                addProperty { name = property.name }
              }
            val getter =
              property
                ?.addGetter { returnType = function.ir.returnType }
                ?.apply { this.overriddenSymbols += function.ir.symbol }
                ?: addOverride(
                  function.ir.kotlinFqName,
                  function.ir.name,
                  function.ir.returnType,
                  overriddenSymbols = listOf(function.ir.symbol),
                )
            getter.apply {
              this.dispatchReceiverParameter = thisReceiverParameter
              this.extensionReceiverParameter = function.ir.extensionReceiverParameter?.copyTo(this)
              body =
                pluginContext.createIrBuilder(symbol).run {
                  irBlockBody(
                    symbol,
                    irInvoke(
                      callee = symbols.stdlibErrorFunction,
                      args = listOf(irString("Never called")),
                    ),
                  )
                }
            }
          }
      }
  }

  private fun collectBindings(
    node: DependencyGraphNode,
    graph: BindingGraph,
    bindingStack: BindingStack,
  ): Map<TypeKey, Binding> {
    val bindingDependencies = mutableMapOf<TypeKey, Binding>()
    // Track used unscoped bindings. We only need to generate a field if they're used more than
    // once
    val usedUnscopedBindings = mutableSetOf<TypeKey>()
    val visitedBindings = mutableSetOf<TypeKey>()

    // Initial pass from each root
    node.exposedTypes.forEach { (accessor, contextualTypeKey) ->
      findAndProcessBinding(
        contextKey = contextualTypeKey,
        stackEntry = BindingStack.Entry.requestedAt(contextualTypeKey, accessor.ir),
        node = node,
        graph = graph,
        bindingStack = bindingStack,
        bindingDependencies = bindingDependencies,
        usedUnscopedBindings = usedUnscopedBindings,
        visitedBindings = visitedBindings,
      )
    }
    return bindingDependencies
  }

  private fun findAndProcessBinding(
    contextKey: ContextualTypeKey,
    stackEntry: BindingStack.Entry,
    node: DependencyGraphNode,
    graph: BindingGraph,
    bindingStack: BindingStack,
    bindingDependencies: MutableMap<TypeKey, Binding>,
    usedUnscopedBindings: MutableSet<TypeKey>,
    visitedBindings: MutableSet<TypeKey>,
  ) {
    val key = contextKey.typeKey
    // Skip if already visited
    if (key in visitedBindings) {
      if (key in usedUnscopedBindings && key !in bindingDependencies) {
        // Only add unscoped binding provider fields if they're used more than once
        bindingDependencies[key] = graph.requireBinding(key)
      }
      return
    }

    bindingStack.withEntry(stackEntry) {
      val binding = graph.getOrCreateBinding(contextKey, bindingStack)
      processBinding(
        binding,
        node,
        graph,
        bindingStack,
        bindingDependencies,
        usedUnscopedBindings,
        visitedBindings,
      )
    }
  }

  private fun processBinding(
    binding: Binding,
    node: DependencyGraphNode,
    graph: BindingGraph,
    bindingStack: BindingStack,
    bindingDependencies: MutableMap<TypeKey, Binding>,
    usedUnscopedBindings: MutableSet<TypeKey>,
    visitedBindings: MutableSet<TypeKey>,
  ) {
    val isMultibindingProvider = binding is Binding.Provided && binding.isMultibindingProvider
    val key = binding.typeKey

    // Skip if already visited
    // TODO de-dupe with findAndProcessBinding
    if (!isMultibindingProvider && key in visitedBindings) {
      if (key in usedUnscopedBindings && key !in bindingDependencies) {
        // Only add unscoped binding provider fields if they're used more than once
        bindingDependencies[key] = graph.requireBinding(key)
      }
      return
    }

    val bindingScope = binding.scope

    // Check scoping compatibility
    // TODO FIR error?
    if (bindingScope != null) {
      if (node.scopes.isEmpty() || bindingScope !in node.scopes) {
        val isUnscoped = node.scopes.isEmpty()
        // Error if there are mismatched scopes
        val declarationToReport = node.sourceGraph
        bindingStack.push(
          BindingStack.Entry.simpleTypeRef(
            binding.contextualTypeKey,
            usage = "(scoped to '$bindingScope')",
          )
        )
        val message = buildString {
          append("[Lattice/IncompatiblyScopedBindings] ")
          append(declarationToReport.kotlinFqName)
          if (isUnscoped) {
            // Unscoped graph but scoped binding
            append(" (unscoped) may not reference scoped bindings:")
          } else {
            // Scope mismatch
            append(
              " (scopes ${node.scopes.joinToString { "'$it'" }}) may not reference bindings from different scopes:"
            )
          }
          appendLine()
          appendBindingStack(bindingStack, short = false)
        }
        declarationToReport.reportError(message)
        exitProcessing()
      }
    }

    visitedBindings += key

    // Scoped, graph, and membersinjector bindings always need (provider) fields
    if (
      bindingScope != null ||
        binding is Binding.GraphDependency ||
        (binding is Binding.MembersInjected && !binding.isFromInjectorFunction)
    ) {
      bindingDependencies[key] = binding
    }

    // For assisted bindings, we need provider fields for the assisted factory impl type
    // The factory impl type depends on a provider of the assisted type
    if (binding is Binding.Assisted) {
      bindingDependencies[key] = binding.target
      // TODO is this safe to end up as a provider field? Can someone create a
      //  binding such that you have an assisted type on the DI graph that is
      //  provided by a provider that depends on the assisted factory? I suspect
      //  yes, so in that case we should probably track a separate field mapping
      usedUnscopedBindings += binding.target.typeKey
      // By definition, these parameters are not available on the graph
      return
    }

    // For multibindings, we depend on anything the delegate providers depend on
    if (binding is Binding.Multibinding) {
      if (bindingScope != null) {
        // This is scoped so we want to keep an instance
        // TODO are these allowed?
        //  bindingDependencies[key] = buildMap {
        //    for (provider in binding.providers) {
        //      putAll(provider.dependencies)
        //    }
        //  }
      } else {
        // Process all providers deps, but don't need a specific dep for this one
        for (provider in binding.providers) {
          processBinding(
            binding = provider,
            node = node,
            graph = graph,
            bindingStack = bindingStack,
            bindingDependencies = bindingDependencies,
            usedUnscopedBindings = usedUnscopedBindings,
            visitedBindings = visitedBindings,
          )
        }
      }
      return
    }

    // Track dependencies before creating fields
    if (bindingScope == null) {
      usedUnscopedBindings += key
    }

    // Recursively process dependencies
    binding.parameters.nonInstanceParameters.forEach { param ->
      // Process binding dependencies
      findAndProcessBinding(
        contextKey = param.contextualTypeKey,
        stackEntry = (param as ConstructorParameter).bindingStackEntry,
        node = node,
        graph = graph,
        bindingStack = bindingStack,
        bindingDependencies = bindingDependencies,
        usedUnscopedBindings = usedUnscopedBindings,
        visitedBindings = visitedBindings,
      )
    }
  }

  private fun IrBuilderWithScope.generateBindingArguments(
    targetParams: Parameters<out Parameter>,
    function: IrFunction,
    binding: Binding,
    generationContext: GraphGenerationContext,
  ): List<IrExpression?> {
    val params = function.parameters(latticeContext)
    // TODO only value args are supported atm
    val paramsToMap = buildList {
      if (
        binding is Binding.Provided &&
          targetParams.instance?.type?.rawTypeOrNull()?.isObject != true
      ) {
        targetParams.instance?.let(::add)
      }
      addAll(targetParams.valueParameters.filterNot { it.isAssisted })
    }
    if (
      binding is Binding.Provided && binding.providerFunction.correspondingPropertySymbol == null
    ) {
      check(params.valueParameters.size == paramsToMap.size) {
        """
          Inconsistent parameter types for type ${binding.typeKey}!
          Input type keys:
            - ${paramsToMap.map { it.typeKey }.joinToString()}
          Binding parameters (${function.kotlinFqName}):
            - ${function.valueParameters.map { ContextualTypeKey.from(latticeContext, it).typeKey }.joinToString()}
        """
          .trimIndent()
      }
    }

    return params.valueParameters.mapIndexed { i, param ->
      val contextualTypeKey = paramsToMap[i].contextualTypeKey
      val typeKey = contextualTypeKey.typeKey

      // TODO consolidate this logic with generateBindingCode
      generationContext.instanceFields[typeKey]?.let { instanceField ->
        // If it's in instance field, invoke that field
        return@mapIndexed irGetField(irGet(generationContext.thisReceiver), instanceField)
      }

      val providerInstance =
        if (typeKey in generationContext.providerFields) {
          // If it's in provider fields, invoke that field
          irGetField(
            irGet(generationContext.thisReceiver),
            generationContext.providerFields.getValue(typeKey),
          )
        } else if (
          binding is Binding.Provided &&
            binding.isMultibindingProvider &&
            binding in generationContext.multibindingProviderFields
        ) {
          irGetField(
            irGet(generationContext.thisReceiver),
            generationContext.multibindingProviderFields.getValue(binding),
          )
        } else {
          val entry =
            when (binding) {
              is Binding.ConstructorInjected -> {
                val constructor = binding.injectedConstructor
                BindingStack.Entry.injectedAt(
                  contextualTypeKey,
                  constructor,
                  constructor.valueParameters[i],
                )
              }
              is Binding.Provided -> {
                BindingStack.Entry.injectedAt(
                  contextualTypeKey,
                  function,
                  function.valueParameters[i],
                )
              }
              is Binding.Assisted -> {
                BindingStack.Entry.injectedAt(contextualTypeKey, function)
              }
              is Binding.MembersInjected -> {
                BindingStack.Entry.injectedAt(contextualTypeKey, function)
              }
              is Binding.Multibinding -> {
                // TODO can't be right?
                BindingStack.Entry.injectedAt(contextualTypeKey, function)
              }
              is Binding.Absent,
              is Binding.BoundInstance,
              is Binding.GraphDependency -> error("Should never happen, logic is handled above")
            }
          generationContext.bindingStack.push(entry)
          // Generate binding code for each param
          val paramBinding =
            generationContext.graph.getOrCreateBinding(
              contextualTypeKey,
              generationContext.bindingStack,
            )

          if (paramBinding is Binding.Absent) {
            // Null argument expressions get treated as absent in the final call
            return@mapIndexed null
          }

          generateBindingCode(paramBinding, generationContext)
        }
      // TODO share logic from InjectConstructorTransformer
      if (param.isWrappedInLazy) {
        // DoubleCheck.lazy(...)
        irInvoke(
          dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
          callee = symbols.doubleCheckLazy,
          typeHint = param.type.wrapInLazy(symbols),
          args = listOf(providerInstance),
        )
      } else if (param.isLazyWrappedInProvider) {
        // ProviderOfLazy.create(provider)
        irInvoke(
          dispatchReceiver = irGetObject(symbols.providerOfLazyCompanionObject),
          callee = symbols.providerOfLazyCreate,
          args = listOf(providerInstance),
          typeHint = param.type.wrapInLazy(symbols).wrapInProvider(symbols.latticeProvider),
        )
      } else if (param.isWrappedInProvider) {
        providerInstance
      } else {
        irInvoke(
          dispatchReceiver = providerInstance,
          callee = symbols.providerInvoke,
          typeHint = param.type,
        )
      }
    }
  }

  private fun IrConstructorCall.shouldUnwrapMapKeyValues(): Boolean {
    val mapKeyMapKeyAnnotation = annotationClass.mapKeyAnnotation()!!.ir
    // TODO FIR check valid mapkey
    //  - single arg
    //  - no generics
    val unwrapValue = mapKeyMapKeyAnnotation.getSingleConstBooleanArgumentOrNull() != false
    return unwrapValue
  }

  private fun IrBuilderWithScope.generateMapKeyLiteral(
    binding: Binding.Provided,
    keyType: IrType,
  ): IrExpression {
    val mapKey = binding.mapKey!!.ir

    val unwrapValue = mapKey.shouldUnwrapMapKeyValues()
    val expression =
      if (!unwrapValue) {
        mapKey
      } else {
        // We can just copy the expression!
        // TODO do we need to call shallowCopy()?
        mapKey.getValueArgument(0)!!
      }

    val typeToCompare =
      if (expression is IrClassReference) {
        // We want KClass<*>, not the specific type in the annotation we got (i.e. KClass<Int>).
        pluginContext.irBuiltIns.kClassClass.starProjectedType
      } else {
        expression.type
      }
    if (typeToCompare != keyType) {
      // TODO check in FIR instead
      error(
        "Map key type mismatch: ${typeToCompare.dumpKotlinLike()} != ${keyType.dumpKotlinLike()}"
      )
    }
    return expression
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  internal fun IrBuilderWithScope.generateBindingCode(
    binding: Binding,
    generationContext: GraphGenerationContext,
    contextualTypeKey: ContextualTypeKey = binding.contextualTypeKey,
    fieldInitKey: TypeKey? = null,
  ): IrExpression {
    if (binding is Binding.Absent) {
      error("Absent bindings need to be checked prior to generateBindingCode()")
    }

    // If we already have a provider field we can just return it
    if (
      binding is Binding.Provided &&
        binding.isMultibindingProvider &&
        binding in generationContext.multibindingProviderFields
    ) {
      generationContext.multibindingProviderFields[binding]?.let {
        return irGetField(irGet(generationContext.thisReceiver), it)
      }
    }

    // If we're initializing the field for this key, don't ever try to reach for an existing
    // provider for it.
    // This is important for cases like DelegateFactory and breaking cycles.
    if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
      generationContext.providerFields[binding.typeKey]?.let {
        return irGetField(irGet(generationContext.thisReceiver), it)
      }
    }

    return when (binding) {
      is Binding.ConstructorInjected -> {
        // Example_Factory.create(...)
        // TODO if there are default params, compute a mask
        val injectableConstructor = binding.injectedConstructor
        val factoryClass =
          injectConstructorTransformer.getOrGenerateFactoryClass(
            binding.type,
            injectableConstructor,
          )
        // Invoke its factory's create() function
        val creatorClass =
          if (factoryClass.isObject) {
            factoryClass
          } else {
            factoryClass.companionObject()!!
          }
        val createFunction = creatorClass.getSimpleFunction(LatticeSymbols.StringNames.Create)!!
        val args =
          generateBindingArguments(
            createFunction.owner.parameters(latticeContext),
            createFunction.owner,
            binding,
            generationContext,
          )
        irInvoke(
          dispatchReceiver = irGetObject(creatorClass.symbol),
          callee = createFunction,
          args = args,
        )
      }

      is Binding.Provided -> {
        // For binds functions, just use the backing type
        binding.aliasedType?.let {
          return generateBindingCode(
            generationContext.graph.getOrCreateBinding(it, generationContext.bindingStack),
            generationContext,
            it,
          )
        }

        val factoryClass = providesTransformer.getOrGenerateFactoryClass(binding)
        // Invoke its factory's create() function
        val creatorClass =
          if (factoryClass.isObject) {
            factoryClass
          } else {
            factoryClass.companionObject()!!
          }
        val createFunction = creatorClass.getSimpleFunction(LatticeSymbols.StringNames.Create)!!
        // Must use the provider's params for TypeKey as that has qualifier
        // annotations
        val args =
          generateBindingArguments(
            binding.parameters,
            createFunction.owner,
            binding,
            generationContext,
          )
        irInvoke(
          dispatchReceiver = irGetObject(creatorClass.symbol),
          callee = createFunction,
          args = args,
        )
      }
      is Binding.Assisted -> {
        // Example9_Factory_Impl.create(example9Provider);
        val implClass = assistedFactoryTransformer.getOrGenerateImplClass(binding.type)
        val implClassCompanion = implClass.companionObject()!!
        val createFunction =
          implClassCompanion.getSimpleFunction(LatticeSymbols.StringNames.Create)!!
        val delegateFactoryProvider = generateBindingCode(binding.target, generationContext)
        irInvoke(
          dispatchReceiver = irGetObject(implClassCompanion.symbol),
          callee = createFunction,
          args = listOf(delegateFactoryProvider),
        )
      }
      is Binding.Multibinding -> {
        if (binding.isSet) {
          val elementType = (binding.typeKey.type as IrSimpleType).arguments.single().typeOrFail
          val (collectionProviders, individualProviders) =
            binding.providers.partition { it.elementsIntoSet }
          check(individualProviders.all { it.intoSet })
          // If we have any @ElementsIntoSet, we need to use SetFactory
          if (collectionProviders.isNotEmpty() || contextualTypeKey.requiresProviderInstance) {
            // SetFactory.<String>builder(1, 1)
            //   .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
            //   .addCollectionProvider(provideString2Provider)
            //   .build()

            // SetFactory.<String>builder(1, 1)
            val builder: IrExpression =
              irInvoke(
                  dispatchReceiver = irGetObject(symbols.setFactoryCompanionObject),
                  callee = symbols.setFactoryBuilderFunction,
                  typeHint = symbols.setFactoryBuilder.typeWith(elementType),
                )
                .apply {
                  putTypeArgument(0, elementType)
                  putValueArgument(0, irInt(individualProviders.size))
                  putValueArgument(1, irInt(collectionProviders.size))
                }

            val withProviders =
              individualProviders.fold(builder) { receiver, provider ->
                irInvoke(
                    dispatchReceiver = receiver,
                    callee = symbols.setFactoryBuilderAddProviderFunction,
                    typeHint = builder.type,
                  )
                  .apply { putValueArgument(0, generateBindingCode(provider, generationContext)) }
              }

            // .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
            val withCollectionProviders =
              collectionProviders.fold(withProviders) { receiver, provider ->
                irInvoke(
                    dispatchReceiver = receiver,
                    callee = symbols.setFactoryBuilderAddCollectionProviderFunction,
                    typeHint = builder.type,
                  )
                  .apply { putValueArgument(0, generateBindingCode(provider, generationContext)) }
              }

            // .build()
            irInvoke(
              dispatchReceiver = withCollectionProviders,
              callee = symbols.setFactoryBuilderBuildFunction,
              typeHint =
                pluginContext.irBuiltIns.setClass
                  .typeWith(elementType)
                  .wrapInProvider(symbols.latticeProvider),
            )
          } else {
            val callee: IrSimpleFunctionSymbol
            val args: List<IrExpression>
            when (val size = binding.providers.size) {
              0 -> {
                // emptySet()
                callee = symbols.emptySet
                args = emptyList()
              }
              1 -> {
                // setOf(<one>)
                callee = symbols.setOfSingleton
                val provider = binding.providers.first()
                args = listOf(generateMultibindingArgument(provider, generationContext))
              }
              else -> {
                // buildSet(<size>) { ... }
                callee = symbols.buildSetWithCapacity
                args = buildList {
                  add(irInt(size))
                  add(
                    irLambda(
                      context = pluginContext,
                      parent = parent,
                      receiverParameter =
                        pluginContext.irBuiltIns.mutableSetClass.typeWith(elementType),
                      valueParameters = emptyList(),
                      returnType = pluginContext.irBuiltIns.unitType,
                      suspend = false,
                    ) { function ->
                      // This is the mutable set receiver
                      val functionReceiver = function.extensionReceiverParameter!!
                      binding.providers.forEach { provider ->
                        +irInvoke(
                          dispatchReceiver = irGet(functionReceiver),
                          callee = symbols.mutableSetAdd.symbol,
                          args = listOf(generateMultibindingArgument(provider, generationContext)),
                        )
                      }
                    }
                  )
                }
              }
            }
            irCall(
                callee = callee,
                type = binding.typeKey.type,
                typeArguments = listOf(elementType),
              )
              .apply {
                for ((i, arg) in args.withIndex()) {
                  putValueArgument(i, arg)
                }
              }
          }
        } else {
          // It's a map
          // MapFactory.<Integer, Integer>builder(2)
          //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
          //   .put(2, provideMapInt2Provider)
          //   .build()
          // MapProviderFactory.<Integer, Integer>builder(2)
          //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
          //   .put(2, provideMapInt2Provider)
          //   .build()
          val mapTypeArgs = (contextualTypeKey.typeKey.type as IrSimpleType).arguments
          check(mapTypeArgs.size == 2) { "Unexpected map type args: ${mapTypeArgs.joinToString()}" }
          val keyType: IrType = mapTypeArgs[0].typeOrFail
          val rawValueType = mapTypeArgs[1].typeOrFail
          val rawValueTypeMetadata =
            rawValueType.typeOrFail.asContextualTypeKey(latticeContext, null, false)
          val useProviderFactory: Boolean = rawValueTypeMetadata.isWrappedInProvider
          val valueType: IrType = rawValueTypeMetadata.typeKey.type

          val targetCompanionObject =
            if (useProviderFactory) {
              symbols.mapProviderFactoryCompanionObject
            } else {
              symbols.mapFactoryCompanionObject
            }
          val builderFunction =
            if (useProviderFactory) {
              symbols.mapProviderFactoryBuilderFunction
            } else {
              symbols.mapFactoryBuilderFunction
            }
          val builderType =
            if (useProviderFactory) {
              symbols.mapProviderFactoryBuilder
            } else {
              symbols.mapFactoryBuilder
            }

          // MapFactory.<Integer, Integer>builder(2)
          // MapProviderFactory.<Integer, Integer>builder(2)
          val builder: IrExpression =
            irInvoke(
                dispatchReceiver = irGetObject(targetCompanionObject),
                callee = builderFunction,
                typeHint = builderType.typeWith(keyType, valueType),
              )
              .apply {
                putTypeArgument(0, keyType)
                putTypeArgument(1, valueType)
                putValueArgument(0, irInt(binding.providers.size))
              }

          val putFunction =
            if (useProviderFactory) {
              symbols.mapProviderFactoryBuilderPutFunction
            } else {
              symbols.mapFactoryBuilderPutFunction
            }
          val putAllFunction =
            if (useProviderFactory) {
              symbols.mapProviderFactoryBuilderPutAllFunction
            } else {
              symbols.mapFactoryBuilderPutAllFunction
            }

          val withProviders =
            binding.providers.fold(builder) { receiver, provider ->
              val providerTypeMetadata = provider.contextualTypeKey

              // TODO FIR this should be an error actually
              val isMap =
                providerTypeMetadata.typeKey.type.rawType().symbol == context.irBuiltIns.mapClass

              val putter =
                if (isMap) {
                  // .putAll(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
                  putAllFunction
                  // TODO is this only for inheriting in GraphExtensions?
                  TODO("putAll isn't yet supported")
                } else {
                  // .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
                  putFunction
                }
              irInvoke(dispatchReceiver = receiver, callee = putter, typeHint = builder.type)
                .apply {
                  putValueArgument(0, generateMapKeyLiteral(provider, keyType))
                  putValueArgument(1, generateBindingCode(provider, generationContext))
                }
            }

          // .build()
          val buildFunction =
            if (useProviderFactory) {
              symbols.mapProviderFactoryBuilderBuildFunction
            } else {
              symbols.mapFactoryBuilderBuildFunction
            }
          irInvoke(
            dispatchReceiver = withProviders,
            callee = buildFunction,
            typeHint =
              pluginContext.irBuiltIns.mapClass
                .typeWith(keyType, rawValueType)
                .wrapInProvider(symbols.latticeProvider),
          )
        }
      }
      is Binding.MembersInjected -> {
        val injectedClass = pluginContext.referenceClass(binding.targetClassId)!!.owner
        val injectorClass = membersInjectorTransformer.getOrGenerateInjector(injectedClass)?.ir

        if (injectorClass == null) {
          // Return a noop
          irInvoke(
              dispatchReceiver = irGetObject(symbols.latticeMembersInjectors),
              callee = symbols.latticeMembersInjectorsNoOp,
            )
            .apply { putTypeArgument(0, injectedClass.typeWith()) }
        } else {
          val createFunction = injectorClass.getSimpleFunction(LatticeSymbols.StringNames.Create)!!
          val args =
            generateBindingArguments(
              binding.parameters,
              createFunction.owner,
              binding,
              generationContext,
            )
          // InstanceFactory.create(...);
          // TODO share logic with above instancefactory
          irInvoke(
              dispatchReceiver = irGetObject(symbols.instanceFactoryCompanionObject),
              callee = symbols.instanceFactoryCreate,
              args =
                listOf(
                  // InjectableClass_MembersInjector.create(stringValueProvider,
                  // exampleComponentProvider)
                  irInvoke(
                    dispatchReceiver = irGetObject(injectorClass.symbol),
                    callee = createFunction,
                    args = args,
                  )
                ),
              typeHint = binding.typeKey.type.wrapInProvider(symbols.latticeFactory),
            )
            .apply { putTypeArgument(0, injectedClass.typeWith()) }
        }
      }
      is Binding.Absent -> {
        // Should never happen, this should be checked before function/constructor injections.
        error("Unable to generate code for unexpected Absent binding: $binding")
      }
      is Binding.BoundInstance -> {
        // Should never happen, this should get handled in the provider fields logic above.
        error("Unable to generate code for unexpected BoundInstance binding: $binding")
      }
      is Binding.GraphDependency -> {
        /*
        TODO eventually optimize this like dagger does and generate static provider classes that don't hold outer refs
        private static final class GetCharSequenceProvider implements Provider<CharSequence> {
          private final CharSequenceGraph charSequenceGraph;

          GetCharSequenceProvider(CharSequenceGraph charSequenceGraph) {
            this.charSequenceGraph = charSequenceGraph;
          }

          @Override
          public CharSequence get() {
            return Preconditions.checkNotNullFromGraph(charSequenceGraph.getCharSequence());
          }
        }
        */

        val graphParameter =
          generationContext.graphTypesToCtorParams[binding.typeKey]
            ?: run {
              error("No matching dependency graph instance found for type $binding.typeKey")
            }
        val lambda =
          irLambda(
            context = pluginContext,
            parent = generationContext.thisReceiver.parent,
            receiverParameter = null,
            emptyList(),
            binding.typeKey.type,
            suspend = false,
          ) { lambdaFunction ->
            +irReturn(
              irInvoke(
                dispatchReceiver = irGet(graphParameter),
                callee = binding.getter.symbol,
                typeHint = binding.typeKey.type,
              )
            )
          }
        irInvoke(
          dispatchReceiver = null,
          callee = symbols.latticeProviderFunction,
          typeHint = binding.typeKey.type.wrapInProvider(symbols.latticeProvider),
          args = listOf(lambda),
        )
      }
    }
  }

  private fun IrBuilderWithScope.generateMultibindingArgument(
    provider: Binding.Provided,
    generationContext: GraphGenerationContext,
  ): IrExpression {
    val bindingCode = generateBindingCode(provider, generationContext)
    return typeAsProviderArgument(
      latticeContext,
      ContextualTypeKey(provider.typeKey, false, false, false, false),
      bindingCode,
      false,
      false,
    )
  }
}

internal class GraphGenerationContext(
  val graph: BindingGraph,
  val thisReceiver: IrValueParameter,
  val instanceFields: Map<TypeKey, IrField>,
  val graphTypesToCtorParams: Map<TypeKey, IrValueParameter>,
  val providerFields: Map<TypeKey, IrField>,
  val multibindingProviderFields: Map<Binding.Provided, IrField>,
  val bindingStack: BindingStack,
)
