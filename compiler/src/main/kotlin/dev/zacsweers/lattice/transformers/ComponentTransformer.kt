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
package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.LatticeOrigin
import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.NameAllocator
import dev.zacsweers.lattice.decapitalizeUS
import dev.zacsweers.lattice.exitProcessing
import dev.zacsweers.lattice.ir.IrAnnotation
import dev.zacsweers.lattice.ir.addCompanionObject
import dev.zacsweers.lattice.ir.addOverride
import dev.zacsweers.lattice.ir.allCallableMembers
import dev.zacsweers.lattice.ir.createIrBuilder
import dev.zacsweers.lattice.ir.doubleCheck
import dev.zacsweers.lattice.ir.getAllSuperTypes
import dev.zacsweers.lattice.ir.getSingleConstBooleanArgumentOrNull
import dev.zacsweers.lattice.ir.irBlockBody
import dev.zacsweers.lattice.ir.irInvoke
import dev.zacsweers.lattice.ir.irLambda
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.ir.isBindsProviderCandidate
import dev.zacsweers.lattice.ir.isExternalParent
import dev.zacsweers.lattice.ir.rawType
import dev.zacsweers.lattice.ir.rawTypeOrNull
import dev.zacsweers.lattice.ir.singleAbstractFunction
import dev.zacsweers.lattice.ir.typeAsProviderArgument
import dev.zacsweers.lattice.letIf
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
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.addMember
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
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class ComponentData {
  val components = mutableMapOf<ClassId, ComponentNode>()
}

internal class ComponentTransformer(context: LatticeTransformerContext) :
  IrElementTransformer<ComponentData>, LatticeTransformerContext by context {

  private val injectConstructorTransformer = InjectConstructorTransformer(context)
  private val assistedFactoryTransformer =
    AssistedFactoryTransformer(context, injectConstructorTransformer)
  private val providesTransformer = ProvidesTransformer(context)

  // Keyed by the source declaration
  private val componentNodesByClass = mutableMapOf<ClassId, ComponentNode>()
  // Keyed by the source declaration
  private val latticeComponentsByClass = mutableMapOf<ClassId, IrClass>()

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  override fun visitCall(expression: IrCall, data: ComponentData): IrElement {
    // Covers replacing createComponentFactory() compiler intrinsics with calls to the real
    // component factory
    val callee = expression.symbol.owner
    when (callee.symbol) {
      symbols.latticeCreateComponentFactory -> {
        // Get the called type
        val type =
          expression.getTypeArgument(0)
            ?: error(
              "Missing type argument for ${symbols.latticeCreateComponentFactory.owner.name}"
            )
        val rawType = type.rawType()
        if (!rawType.isAnnotatedWithAny(symbols.componentFactoryAnnotations)) {
          // TODO FIR error
          error(
            "Cannot create a component factory instance of non-factory type ${rawType.kotlinFqName}"
          )
        }
        val componentDeclaration = rawType.parentAsClass
        val componentClass = getOrBuildComponent(componentDeclaration)
        val componentCompanion = componentClass.companionObject()!!
        val factoryFunction = componentCompanion.getSimpleFunction("factory")!!
        // Replace it with a call directly to the factory function
        return pluginContext.createIrBuilder(expression.symbol).run {
          irCall(callee = factoryFunction, type = type).apply {
            dispatchReceiver = irGetObject(componentCompanion.symbol)
          }
        }
      }
      symbols.latticeCreateComponent -> {
        // Get the called type
        val type =
          expression.getTypeArgument(0)
            ?: error("Missing type argument for ${symbols.latticeCreateComponent.owner.name}")
        val rawType = type.rawType()
        if (!rawType.isAnnotatedWithAny(symbols.componentAnnotations)) {
          // TODO FIR error
          error("Cannot create a component instance of non-component type ${rawType.kotlinFqName}")
        }
        val componentClass = getOrBuildComponent(rawType)
        val componentCompanion = componentClass.companionObject()!!
        val factoryFunction = componentCompanion.getSimpleFunction("create")!!
        // Replace it with a call directly to the create function
        return pluginContext.createIrBuilder(expression.symbol).run {
          irCall(callee = factoryFunction, type = type).apply {
            dispatchReceiver = irGetObject(componentCompanion.symbol)
          }
        }
      }
    }

    return super.visitCall(expression, data)
  }

  override fun visitProperty(declaration: IrProperty, data: ComponentData): IrStatement {
    providesTransformer.visitProperty(declaration)
    return super.visitProperty(declaration, data)
  }

  override fun visitFunction(declaration: IrFunction, data: ComponentData): IrStatement {
    if (declaration is IrSimpleFunction) {
      providesTransformer.visitFunction(declaration)
    }
    return super.visitFunction(declaration, data)
  }

  override fun visitClass(declaration: IrClass, data: ComponentData): IrStatement {
    log("Reading <$declaration>")

    // TODO need to better divvy these
    injectConstructorTransformer.visitClass(declaration)
    assistedFactoryTransformer.visitClass(declaration)

    val isAnnotatedWithComponent = declaration.isAnnotatedWithAny(symbols.componentAnnotations)
    if (!isAnnotatedWithComponent) return super.visitClass(declaration, data)

    providesTransformer.visitClass(declaration)

    getOrBuildComponent(declaration)

    // TODO dump option to detect unused

    return super.visitClass(declaration, data)
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun getOrComputeComponentNode(
    componentDeclaration: IrClass,
    componentDependencyStack: BindingStack,
    generatedComponentId: ClassId =
      componentDeclaration.classIdOrFail.createNestedClassId(LatticeSymbols.Names.LatticeComponent),
  ): ComponentNode {
    val componentClassId = componentDeclaration.classIdOrFail
    componentNodesByClass[componentClassId]?.let {
      return it
    }

    val componentTypeKey = TypeKey(componentDeclaration.typeWith())
    if (componentDependencyStack.entryFor(componentTypeKey) != null) {
      // TODO dagger doesn't appear to error for this case to model off of
      val message = buildString {
        if (componentDependencyStack.entries.size == 1) {
          // If there's just one entry, specify that it's a self-referencing cycle for clarity
          appendLine(
            "[Lattice/ComponentDependencyCycle] Component dependency cycle detected! The below component depends on itself."
          )
        } else {
          appendLine("[Lattice/ComponentDependencyCycle] Component dependency cycle detected!")
        }
        appendBindingStack(componentDependencyStack)
      }
      componentDeclaration.reportError(message)
      exitProcessing()
    }

    componentDeclaration.constructors.forEach { constructor ->
      if (constructor.valueParameters.isNotEmpty()) {
        // TODO dagger doesn't appear to error for this case to model off of
        constructor.reportError(
          "Components cannot have constructors. Use @Component.Factory instead."
        )
        exitProcessing()
      }
    }

    // TODO not currently reading supertypes yet.
    val scopes = componentDeclaration.scopeAnnotations()

    val providerFunctions =
      componentDeclaration
        .getAllSuperTypes(pluginContext, excludeSelf = false)
        .flatMap { it.classOrFail.owner.allCallableMembers() }
        .filterNot { function ->
          // Skip fake overrides. These are types that are inherited from a supertype but
          // not actually user-implemented in the subtype
          function.isFakeOverride ||
            function.correspondingPropertySymbol?.owner?.isFakeOverride == true
        }
        // TODO is this enough for properties like @get:Provides
        .filter { function ->
          function.isAnnotatedWithAny(symbols.providesAnnotations) ||
            function.correspondingPropertySymbol
              ?.owner
              ?.isAnnotatedWithAny(symbols.providesAnnotations) == true
        }
        .map { function -> ContextualTypeKey.from(this, function).typeKey to function }
        .toList()

    val exposedTypes =
      componentDeclaration
        .allCallableMembers()
        .filter { function ->
          // Abstract check is important. We leave alone any non-providers
          function.modality == Modality.ABSTRACT &&
            function.valueParameters.isEmpty() &&
            function.body == null &&
            !function.isBindsProviderCandidate(symbols) &&
            // TODO is this enough for properties like @get:Provides
            !function.isAnnotatedWithAny(symbols.providesAnnotations) &&
            function.correspondingPropertySymbol
              ?.owner
              ?.isAnnotatedWithAny(symbols.providesAnnotations) != true
        }
        .associateWith { function -> ContextualTypeKey.from(this, function) }

    val creator =
      componentDeclaration.nestedClasses
        .singleOrNull { klass -> klass.isAnnotatedWithAny(symbols.componentFactoryAnnotations) }
        ?.let { factory ->
          // Validated in FIR so we can assume we'll find just one here
          // TODO support properties? Would be odd but technically possible
          val createFunction = factory.singleAbstractFunction(this)
          ComponentNode.Creator(factory, createFunction, createFunction.parameters(this))
        }

    val componentDependencies =
      creator
        ?.parameters
        ?.valueParameters
        .orEmpty()
        .filter { !it.isBindsInstance }
        .map {
          val type = it.typeKey.type.rawType()
          componentDependencyStack.withEntry(
            BindingStackEntry.requestedAt(componentTypeKey, creator!!.createFunction)
          ) {
            getOrComputeComponentNode(type, componentDependencyStack)
          }
        }

    val componentNode =
      ComponentNode(
        sourceComponent = componentDeclaration,
        generatedComponentId = generatedComponentId,
        isAnnotatedWithComponent = true,
        dependencies = componentDependencies,
        scopes = scopes,
        providerFunctions = providerFunctions,
        exposedTypes = exposedTypes,
        isExternal = false,
        creator = creator,
        typeKey = componentTypeKey,
      )
    componentNodesByClass[componentClassId] = componentNode
    return componentNode
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun getOrBuildComponent(componentDeclaration: IrClass): IrClass {
    val componentClassId = componentDeclaration.classIdOrFail
    latticeComponentsByClass[componentClassId]?.let {
      return it
    }

    if (componentDeclaration.isExternalParent) {
      // Externally compiled, look up its generated class
      // TODO won't be visible until we add metadata to generated classes
      val generatedComponent =
        componentDeclaration.nestedClasses.singleOrNull {
          it.name == LatticeSymbols.Names.LatticeComponent
        }
      if (generatedComponent == null) {
        error("Expected generated component for $componentClassId")
      } else {
        latticeComponentsByClass[componentClassId] = generatedComponent
        return generatedComponent
      }
    }

    val componentNode =
      getOrComputeComponentNode(componentDeclaration, BindingStack(componentDeclaration))

    val bindingGraph = createBindingGraph(componentNode)
    bindingGraph.validate(componentNode) { message ->
      componentDeclaration.reportError(message)
      exitProcessing()
    }

    val latticeComponent = generateLatticeComponent(componentNode, bindingGraph)

    // TODO consolidate logic
    latticeComponent.dumpToLatticeLog()
    componentDeclaration.addChild(latticeComponent)
    latticeComponentsByClass[componentClassId] = latticeComponent
    return latticeComponent
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun createBindingGraph(component: ComponentNode): BindingGraph {
    val graph = BindingGraph(this)

    // Add explicit bindings from @Provides methods
    val bindingStack = BindingStack(component.sourceComponent)
    component.providerFunctions.forEach { (typeKey, function) ->
      // TODO these annotation searches are greedy. Need a single-pass lookup

      var isBinds = function.isBindsProviderCandidate(symbols)

      val parameters = function.parameters(this)

      val bindsImplType =
        if (isBinds) {
          parameters.extensionReceiver!!.contextualTypeKey
        } else {
          null
        }

      val provider =
        Binding.Provided(
          providerFunction = function,
          contextualTypeKey = ContextualTypeKey.from(this, function),
          parameters = parameters,
          scope = function.scopeAnnotation(),
          // TODO FIR only one annotation is allowed
          // TODO FIR no scopes on multibindings
          // TODO FIR can't mix @Multibinds and @Provides
          intoSet = function.isAnnotatedWithAny(symbols.latticeClassIds.intoSetAnnotations),
          elementsIntoSet =
            function.isAnnotatedWithAny(symbols.latticeClassIds.elementsIntoSetAnnotations),
          intoMap = function.isAnnotatedWithAny(symbols.latticeClassIds.intoMapAnnotations),
          mapKey =
            function.annotations
              .firstOrNull { annotation ->
                val annotationClass = annotation.symbol.owner.parentAsClass
                for (id in symbols.latticeClassIds.mapKeyAnnotations) {
                  if (annotationClass.hasAnnotation(id)) return@firstOrNull true
                }
                false
              }
              ?.let(::IrAnnotation),
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
    component.creator?.parameters?.valueParameters.orEmpty().forEach {
      graph.addBinding(it.typeKey, Binding.BoundInstance(it), bindingStack)
    }

    component.exposedTypes.forEach { getter, contextualTypeKey ->
      val annotationContainer: IrAnnotationContainer =
        getter.correspondingPropertySymbol?.owner ?: getter
      val isMultibindingDeclaration =
        annotationContainer.isAnnotatedWithAny(symbols.latticeClassIds.multibindsAnnotations)

      if (isMultibindingDeclaration) {
        graph.addBinding(
          contextualTypeKey.typeKey,
          Binding.Multibinding.create(pluginContext, contextualTypeKey.typeKey),
          bindingStack,
        )
      }
    }

    // Add bindings from component dependencies
    component.dependencies.forEach { depNode ->
      depNode.exposedTypes.forEach { (getter, contextualTypeKey) ->
        graph.addBinding(
          contextualTypeKey.typeKey,
          Binding.ComponentDependency(
            component = depNode.sourceComponent,
            getter = getter,
            typeKey = contextualTypeKey.typeKey,
          ),
          bindingStack,
        )
      }
    }

    // Don't eagerly create bindings for injectable types, they'll be created on-demand
    // when dependencies are analyzed
    // TODO collect unused bindings?

    return graph
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun generateLatticeComponent(node: ComponentNode, graph: BindingGraph): IrClass {
    /*
    Simple object that exposes a factory function

    public static ExampleComponent.Factory factory() {
      return new Factory();
    }

    public static ExampleComponent create() {
      return new Factory().create();
    }

    private static final class Factory implements ExampleComponent.Factory {
      @Override
      public ExampleComponent create() {
        return new ExampleComponentImpl();
      }
    }
    */
    return pluginContext.irFactory
      .buildClass {
        name = node.generatedComponentId.shortClassName
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

        val componentImpl = generateComponentImpl(node, graph)
        componentImpl.parent = this
        addMember(componentImpl)

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
                        irCall(componentImpl.primaryConstructor!!.symbol).apply {
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
              markJvmStatic()
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
            addFunction("create", node.sourceComponent.typeWith(), isStatic = true).apply {
              this.dispatchReceiverParameter = thisReceiver?.copyTo(this)
              this.origin = LatticeOrigin
              this.visibility = DescriptorVisibilities.PUBLIC
              markJvmStatic()
              body =
                pluginContext.createIrBuilder(symbol).run {
                  irBlockBody(
                    symbol,
                    irCallConstructor(componentImpl.primaryConstructor!!.symbol, emptyList()),
                  )
                }
            }
          }
        }
      }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun generateComponentImpl(node: ComponentNode, graph: BindingGraph): IrClass {
    val componentImplName = "${node.sourceComponent.name.asString()}Impl"
    return pluginContext.irFactory
      .buildClass { name = Name.identifier(componentImplName) }
      .apply {
        superTypes += node.sourceComponent.typeWith()
        origin = LatticeOrigin

        createImplicitParameterDeclarationWithWrappedDescriptor()
        val ctor =
          addSimpleDelegatingConstructor(
            node.sourceComponent.primaryConstructor ?: symbols.anyConstructor,
            pluginContext.irBuiltIns,
            isPrimary = true,
            origin = LatticeOrigin,
          )

        // Add fields for providers. May include both scoped and unscoped providers as well as bound
        // instances
        val providerFields = mutableMapOf<TypeKey, IrField>()
        val multibindingProviderFields = mutableMapOf<Binding.Provided, IrField>()
        val componentTypesToCtorParams = mutableMapOf<TypeKey, IrValueParameter>()

        node.creator?.let { creator ->
          for (param in creator.parameters.valueParameters) {
            val isBindsInstance = param.isBindsInstance

            val irParam = ctor.addValueParameter(param.name.asString(), param.type)

            if (isBindsInstance) {
              providerFields[param.typeKey] =
                addField(
                    fieldName = "${param.name}Instance",
                    fieldType = symbols.latticeProvider.typeWith(param.type),
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
                            args = listOf(irGet(irParam)),
                            typeHint = param.type.wrapInProvider(symbols.latticeFactory),
                          )
                        )
                      }
                  }
            } else {
              // It's a component dep. Add all its exposed types as available keys and point them at
              // this constructor parameter for provider field initialization
              for (componentDep in node.allDependencies) {
                for ((_, contextualTypeKey) in componentDep.exposedTypes) {
                  componentTypesToCtorParams[contextualTypeKey.typeKey] = irParam
                }
              }
            }
          }
        }

        // Add fields for this component and other instance params
        // TODO just make this a component field instead?
        val instanceFields = mutableMapOf<TypeKey, IrField>()
        val thisReceiverParameter = thisReceiver!!
        val thisComponentField =
          addField(
              fieldName = componentImplName.decapitalizeUS(),
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

        instanceFields[node.typeKey] = thisComponentField
        // Add convenience mappings for all supertypes to this field so
        // instance providers from inherited types use this instance
        for (superType in node.sourceComponent.getAllSuperTypes(pluginContext)) {
          instanceFields[TypeKey(superType)] = thisComponentField
        }

        // Track a stack for bindings
        val bindingStack = BindingStack(node.sourceComponent)

        // First pass: collect bindings and their dependencies for provider field ordering
        val bindingDependencies = collectBindings(node, graph, bindingStack)

        // Compute safe initialization order
        val initOrder =
          bindingDependencies.entries
            .sortedWith { (a, _), (b, _) ->
              when {
                // If b depends on a, a should be initialized first
                a in (bindingDependencies[b]?.dependencies.orEmpty()) -> -1
                // If a depends on b, b should be initialized first
                b in (bindingDependencies[a]?.dependencies.orEmpty()) -> 1
                // Otherwise order doesn't matter, fall back to just type order for idempotence
                else -> a.compareTo(b)
              }
            }
            .map { it.value }
            .distinct()

        val generationContext =
          ComponentGenerationContext(
            graph,
            thisReceiverParameter,
            instanceFields,
            componentTypesToCtorParams,
            providerFields,
            multibindingProviderFields,
            bindingStack,
          )

        val fieldNameAllocator = NameAllocator()

        // Create fields in dependency-order
        initOrder.forEach { binding ->
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
                      generateBindingCode(binding, generationContext).letIf(binding.scope != null) {
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
            compareBy<Map.Entry<IrSimpleFunction, ContextualTypeKey>> { it.key.name }
              .thenComparing { it.value.typeKey }
          )
          .forEach { (function, contextualTypeKey) ->
            val key = contextualTypeKey.typeKey
            val property =
              function.correspondingPropertySymbol?.owner?.let { property ->
                addProperty { name = property.name }
              }
            val getter =
              property
                ?.addGetter { returnType = function.returnType }
                ?.apply { this.overriddenSymbols += function.symbol }
                ?: addOverride(
                  function.kotlinFqName,
                  function.name,
                  function.returnType,
                  overriddenSymbols = listOf(function.symbol),
                )
            getter.apply {
              this.dispatchReceiverParameter = thisReceiverParameter
              val binding = graph.getOrCreateBinding(contextualTypeKey, BindingStack.empty())
              bindingStack.push(BindingStackEntry.requestedAt(key, function))
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
                      this@ComponentTransformer,
                      contextualTypeKey,
                      generateBindingCode(binding, generationContext, contextualTypeKey),
                      isAssisted = false,
                      isComponentInstance = false,
                      symbols,
                    ),
                  )
                }
            }
            bindingStack.pop()
          }

        // Implement no-op bodies for Binds providers
        node.providerFunctions
          // TODO this isn't a great check. Let's refactor once there's a ProviderHolder class
          .filter { it.second.body == null }
          .filter { (key, _) ->
            val binding = graph.requireBinding(key) as Binding.Provided
            binding.aliasedType != null
          }
          // Stable sort. First the name then the type
          .sortedWith(
            compareBy<Pair<TypeKey, IrSimpleFunction>> { it.second.name }.thenComparing { it.first }
          )
          .forEach { (_, function) ->
            // TODO dedupe with accessors gen
            val property =
              function.correspondingPropertySymbol?.owner?.let { property ->
                addProperty { name = property.name }
              }
            val getter =
              property
                ?.addGetter { returnType = function.returnType }
                ?.apply { this.overriddenSymbols += function.symbol }
                ?: addOverride(
                  function.kotlinFqName,
                  function.name,
                  function.returnType,
                  overriddenSymbols = listOf(function.symbol),
                )
            getter.apply {
              this.dispatchReceiverParameter = thisReceiverParameter
              this.extensionReceiverParameter = function.extensionReceiverParameter?.copyTo(this)
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
    node: ComponentNode,
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
        stackEntry = BindingStackEntry.requestedAt(contextualTypeKey.typeKey, accessor),
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
    stackEntry: BindingStackEntry,
    node: ComponentNode,
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
    node: ComponentNode,
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
        val componentIsUnscoped = node.scopes.isEmpty()
        // Error if there are mismatched scopes
        val declarationToReport = node.sourceComponent
        bindingStack.push(
          BindingStackEntry.simpleTypeRef(key, usage = "(scoped to '$bindingScope')")
        )
        val message = buildString {
          append("[Lattice/IncompatiblyScopedBindings] ")
          append(declarationToReport.kotlinFqName)
          if (componentIsUnscoped) {
            // Unscoped component but scoped binding
            append(" (unscoped) may not reference scoped bindings:")
          } else {
            // Scope mismatch
            append(
              " (scopes ${node.scopes.joinToString { "'$it'" }}) may not reference bindings from different scopes:"
            )
          }
          appendLine()
          appendBindingStack(bindingStack)
        }
        declarationToReport.reportError(message)
        exitProcessing()
      }
    }

    visitedBindings += key

    // Scoped and component bindings always need (provider) fields
    if (bindingScope != null || binding is Binding.ComponentDependency) {
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
      val depKey = param.typeKey
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
    targetParams: Parameters,
    function: IrFunction,
    binding: Binding,
    generationContext: ComponentGenerationContext,
  ): List<IrExpression?> {
    val params = function.parameters(this@ComponentTransformer)
    // TODO only value args are supported atm
    val paramsToMap = buildList {
      // Can't use isStatic here because companion object functions actually have
      // dispatch receivers
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
            - ${function.valueParameters.map { ContextualTypeKey.from(this@ComponentTransformer, it).typeKey }.joinToString()}
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
                BindingStackEntry.injectedAt(typeKey, constructor, constructor.valueParameters[i])
              }
              is Binding.Provided -> {
                BindingStackEntry.injectedAt(typeKey, function, function.valueParameters[i])
              }
              is Binding.Assisted -> {
                BindingStackEntry.injectedAt(typeKey, function)
              }
              is Binding.Multibinding -> {
                // TODO can't be right?
                BindingStackEntry.injectedAt(typeKey, function)
              }
              is Binding.Absent,
              is Binding.BoundInstance,
              is Binding.ComponentDependency -> error("Should never happen, logic is handled above")
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
  private fun IrBuilderWithScope.generateBindingCode(
    binding: Binding,
    generationContext: ComponentGenerationContext,
    contextualTypeKey: ContextualTypeKey = binding.contextualTypeKey,
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
    generationContext.providerFields[binding.typeKey]?.let {
      return irGetField(irGet(generationContext.thisReceiver), it)
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
        val createFunction = creatorClass.getSimpleFunction("create")!!
        val args =
          generateBindingArguments(
            // Must use the injectable constructor's params for TypeKey as that
            // has qualifier annotations
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

      is Binding.Provided -> {
        // TODO if there are default params, compute a mask
        // TODO what about inherited/overridden providers?

        // For binds functions, just use the backing type
        binding.aliasedType?.let {
          return generateBindingCode(
            generationContext.graph.getOrCreateBinding(it, generationContext.bindingStack),
            generationContext,
            it,
          )
        }

        //  https://github.com/evant/kotlin-inject?tab=readme-ov-file#component-inheritance
        val factoryClass = providesTransformer.getOrGenerateFactoryClass(binding)
        // Invoke its factory's create() function
        val creatorClass =
          if (factoryClass.isObject) {
            factoryClass
          } else {
            factoryClass.companionObject()!!
          }
        val createFunction = creatorClass.getSimpleFunction("create")!!
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
        val createFunction = implClassCompanion.getSimpleFunction("create")!!
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
            rawValueType.typeOrFail.asContextualTypeKey(this@ComponentTransformer, null, false)
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
                  // TODO is this only for inheriting in subcomponents?
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
      is Binding.Absent -> {
        // Should never happen, this should be checked before function/constructor injections.
        error("Unable to generate code for unexpected Absent binding: $binding")
      }
      is Binding.BoundInstance -> {
        // Should never happen, this should get handled in the provider fields logic above.
        error("Unable to generate code for unexpected BoundInstance binding: $binding")
      }
      is Binding.ComponentDependency -> {
        /*
        TODO eventually optimize this like dagger does and generate static provider classes that don't hold outer refs
        private static final class GetCharSequenceProvider implements Provider<CharSequence> {
          private final CharSequenceComponent charSequenceComponent;

          GetCharSequenceProvider(CharSequenceComponent charSequenceComponent) {
            this.charSequenceComponent = charSequenceComponent;
          }

          @Override
          public CharSequence get() {
            return Preconditions.checkNotNullFromComponent(charSequenceComponent.getCharSequence());
          }
        }
        */

        val componentParameter =
          generationContext.componentTypesToCtorParams[binding.typeKey]
            ?: run { error("No matching component instance found for type $binding.typeKey") }
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
                dispatchReceiver = irGet(componentParameter),
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
    generationContext: ComponentGenerationContext,
  ): IrExpression {
    val bindingCode = generateBindingCode(provider, generationContext)
    return typeAsProviderArgument(
      this@ComponentTransformer,
      ContextualTypeKey(provider.typeKey, false, false, false, false),
      bindingCode,
      false,
      false,
      symbols,
    )
  }
}

private class ComponentGenerationContext(
  val graph: BindingGraph,
  val thisReceiver: IrValueParameter,
  val instanceFields: Map<TypeKey, IrField>,
  val componentTypesToCtorParams: Map<TypeKey, IrValueParameter>,
  val providerFields: Map<TypeKey, IrField>,
  val multibindingProviderFields: Map<Binding.Provided, IrField>,
  val bindingStack: BindingStack,
)
