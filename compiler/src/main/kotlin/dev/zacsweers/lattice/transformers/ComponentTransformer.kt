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
import dev.zacsweers.lattice.decapitalizeUS
import dev.zacsweers.lattice.ir.addCompanionObject
import dev.zacsweers.lattice.ir.addOverride
import dev.zacsweers.lattice.ir.allCallableMembers
import dev.zacsweers.lattice.ir.createIrBuilder
import dev.zacsweers.lattice.ir.irInvoke
import dev.zacsweers.lattice.ir.irLambda
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.ir.rawType
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.addMember
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getKFunctionType
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class ComponentData {
  val components = mutableMapOf<ClassId, ComponentNode>()
}

internal class ComponentTransformer(context: LatticeTransformerContext) :
  IrElementTransformer<ComponentData>, LatticeTransformerContext by context {

  private val injectConstructorTransformer = InjectConstructorTransformer(context)
  // Keyed by the source declaration
  private val componentNodesByClass = mutableMapOf<ClassId, ComponentNode>()
  // Keyed by the source declaration
  private val latticeComponentsByClass = mutableMapOf<ClassId, IrClass>()

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  override fun visitCall(expression: IrCall, data: ComponentData): IrElement {
    // Covers replacing createComponentFactory() compiler intrinsics with calls to the real
    // component factory
    val callee = expression.symbol.owner
    if (callee.symbol == symbols.latticeCreateComponentFactory) {
      // Get the called type
      val type =
        expression.getTypeArgument(0) ?: error("Missing type argument for createComponentFactory")
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

    return super.visitCall(expression, data)
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  override fun visitClass(declaration: IrClass, data: ComponentData): IrStatement {
    log("Reading <$declaration>")

    // TODO need to better divvy these
    injectConstructorTransformer.visitClass(declaration)

    val isAnnotatedWithComponent = declaration.isAnnotatedWithAny(symbols.componentAnnotations)
    if (!isAnnotatedWithComponent) return super.visitClass(declaration, data)

    getOrBuildComponent(declaration)
    // TODO dump option to detect unused

    return super.visitClass(declaration, data)
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun getOrComputeComponentNode(componentDeclaration: IrClass): ComponentNode {
    val componentClassId = componentDeclaration.classIdOrFail
    componentNodesByClass[componentClassId]?.let {
      return it
    }

    // TODO not currently reading supertypes yet
    val scope = componentDeclaration.scopeAnnotation()

    val providedMethods =
      componentDeclaration
        .allCallableMembers()
        // TODO is this enough for properties like @get:Provides
        .filter { function -> function.isAnnotatedWithAny(symbols.providesAnnotations) }
        // TODO validate
        .toList()

    val exposedTypes =
      componentDeclaration
        .allCallableMembers()
        .filter { function ->
          function.modality == Modality.ABSTRACT &&
            function.valueParameters.isEmpty() &&
            function.body == null &&
            // TODO is this enough for properties like @get:Provides
            !function.isAnnotatedWithAny(symbols.providesAnnotations)
        }
        // TODO validate
        .associate { function ->
          val qualifier =
            function.correspondingPropertySymbol?.owner?.qualifierAnnotation()
              ?: function.qualifierAnnotation()
          TypeKey(function.returnType, qualifier) to function
        }

    val creator =
      componentDeclaration.nestedClasses
        .single { klass -> klass.isAnnotatedWithAny(symbols.componentFactoryAnnotations) }
        .let { factory ->
          val primaryConstructor = componentDeclaration.primaryConstructor
          ComponentNode.Creator(
            factory,
            factory.functions
              .single { function ->
                function.modality == Modality.ABSTRACT && function.body == null
              }
              .also {
                if (primaryConstructor == null) {
                  // No params to validate
                  return@also
                }
                // TODO FIR error
                val actualTypes = it.valueParameters.map { param -> param.type }
                val expectedTypes = primaryConstructor.valueParameters.map { param -> param.type }
                check(actualTypes == expectedTypes) {
                  buildString {
                    appendLine("Parameter mismatch from factory to primary constructor")
                    val missingParameters = buildList {
                      for (i in expectedTypes.indices) {
                        if (i >= actualTypes.size || expectedTypes[i] != actualTypes[i]) {
                          add(primaryConstructor.valueParameters[i])
                        }
                      }
                    }
                    appendLine(
                      "Missing/mismatched parameters:\n${missingParameters.joinToString("\n") { "- ${it.name}: ${it.type.render()}" }}"
                    )
                  }
                }
              },
          )
        }

    val componentNode =
      ComponentNode(
        sourceComponent = componentDeclaration,
        isAnnotatedWithComponent = true,
        dependencies = emptyList(),
        scope = scope,
        providedFunctions = providedMethods,
        exposedTypes = exposedTypes,
        isExternal = false,
        creator = creator,
      )
    componentNodesByClass[componentClassId] = componentNode
    return componentNode
  }

  private fun getOrBuildComponent(componentDeclaration: IrClass): IrClass {
    val componentClassId = componentDeclaration.classIdOrFail
    latticeComponentsByClass[componentClassId]?.let {
      return it
    }

    val componentNode = getOrComputeComponentNode(componentDeclaration)

    val bindingGraph = createBindingGraph(componentNode)
    bindingGraph.validate()

    val latticeComponent = generateLatticeComponent(componentNode, bindingGraph)

    // TODO consolidate logic
    latticeComponent.dumpToLatticeLog()
    componentDeclaration.getPackageFragment().addChild(latticeComponent)
    latticeComponentsByClass[componentClassId] = latticeComponent
    return latticeComponent
  }

  private fun createBindingGraph(component: ComponentNode): BindingGraph {
    val graph = BindingGraph(this)

    // Add explicit bindings from @Provides methods
    val bindingStack = BindingStack(component.sourceComponent)
    component.providedFunctions.forEach { function ->
      val key = TypeKey(function.returnType, function.qualifierAnnotation())
      val dependencies =
        function.valueParameters.mapToConstructorParameters(this).associateBy { it.typeKey }
      graph.addBinding(
        key,
        Binding.Provided(function, dependencies, function.qualifierAnnotation()),
        bindingStack,
      )
    }

    // Add bindings from component dependencies
    component.dependencies.forEach { dep ->
      dep.exposedTypes.forEach { key ->
        graph.addBinding(
          key,
          Binding.ComponentDependency(component = dep.type, getter = dep.getter),
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
        name = Name.identifier("Lattice${node.sourceComponent.name.asString()}")
        kind = ClassKind.OBJECT
      }
      .apply {
        this.origin = LatticeOrigin
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
                    irExprBody(
                      irCall(componentImpl.primaryConstructor!!.symbol).apply {
                        for (param in valueParameters) {
                          putValueArgument(param.index, irGet(param))
                        }
                      }
                    )
                  }
              }
            }

        factoryClass.parent = this
        addMember(factoryClass)

        pluginContext.irFactory.addCompanionObject(symbols, parent = this) {
          addFunction("factory", factoryClass.typeWith(), isStatic = true).apply {
            this.copyTypeParameters(typeParameters)
            this.dispatchReceiverParameter = thisReceiver?.copyTo(this)
            this.origin = LatticeOrigin
            this.visibility = DescriptorVisibilities.PUBLIC
            markJvmStatic()
            body =
              pluginContext.createIrBuilder(symbol).run {
                irExprBody(irCallConstructor(factoryClass.primaryConstructor!!.symbol, emptyList()))
              }
          }
        }
      }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun generateComponentImpl(node: ComponentNode, graph: BindingGraph): IrClass {
    return pluginContext.irFactory
      .buildClass { name = Name.identifier("${node.sourceComponent.name.asString()}Impl") }
      .apply {
        superTypes += node.sourceComponent.typeWith()
        origin = LatticeOrigin

        createImplicitParameterDeclarationWithWrappedDescriptor()
        addSimpleDelegatingConstructor(
          node.sourceComponent.primaryConstructor ?: symbols.anyConstructor,
          pluginContext.irBuiltIns,
          isPrimary = true,
          origin = LatticeOrigin,
        )

        // Add fields for scoped providers
        val scopedFields = mutableMapOf<TypeKey, IrField>()
        val scopedDependencies = mutableMapOf<TypeKey, Map<TypeKey, Parameter>>()

        // Track a stack for bindings
        val bindingStack = BindingStack(node.sourceComponent)

        // First pass: collect scoped bindings and their dependencies for ordering
        node.exposedTypes.forEach { (key, accessor) ->
          bindingStack.push(BindingStackEntry.requestedAt(key, accessor))
          val binding = graph.getOrCreateBinding(key, bindingStack)
          val bindingScope = binding.scope

          if (bindingScope != null && bindingScope == node.scope) {
            // Track scoped dependencies before creating fields
            scopedDependencies[key] = binding.dependencies
          }
        }

        // Compute safe initialization order
        val initOrder =
          scopedDependencies.keys.sortedWith { a, b ->
            when {
              // If b depends on a, a should be initialized first
              a in (scopedDependencies[b] ?: emptyMap()) -> -1
              // If a depends on b, b should be initialized first
              b in (scopedDependencies[a] ?: emptyMap()) -> 1
              // Otherwise order doesn't matter
              else -> 0
            }
          }

        // Create fields in dependency-order
        initOrder.forEach { key ->
          val binding = graph.getOrCreateBinding(key, BindingStack.empty())
          scopedFields[key] =
            addField(
                fieldName = binding.nameHint.decapitalizeUS() + "Provider",
                fieldType = symbols.latticeProvider.typeWith(key.type),
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .apply {
                isFinal = true
                // DoubleCheck.provider(<provider>)
                initializer =
                  pluginContext.createIrBuilder(symbol).run {
                    irExprBody(
                      irInvoke(
                        dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
                        callee = symbols.doubleCheckProvider,
                        typeHint = null,
                        generateBindingCode(
                          binding,
                          graph,
                          thisReceiver!!,
                          scopedFields,
                          bindingStack,
                        ),
                      )
                    )
                  }
              }
        }

        // Implement abstract getters for exposed types
        node.exposedTypes.entries
          .sortedBy { (_, function) ->
            // TODO also sort by type keys
            function.name.asString()
          }
          .forEach { (key, function) ->
            addOverride(function.kotlinFqName, function.name.asString(), key.type).apply {
              this.dispatchReceiverParameter = thisReceiver!!
              this.overriddenSymbols += function.symbol
              val binding = graph.getOrCreateBinding(key, BindingStack.empty())
              bindingStack.push(BindingStackEntry.requestedAt(key, function))
              body =
                pluginContext.createIrBuilder(symbol).run {
                  irExprBody(
                    if (key in scopedFields) {
                      irInvoke(
                        dispatchReceiver =
                          irGetField(irGet(thisReceiver!!), scopedFields.getValue(key)),
                        callee = symbols.providerInvoke,
                      )
                    } else {
                      generateBindingCode(
                        binding,
                        graph,
                        thisReceiver!!,
                        scopedFields,
                        bindingStack,
                      )
                    }
                  )
                }
              bindingStack.pop()
            }
          }
      }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun DeclarationIrBuilder.generateBindingArguments(
    params: List<Parameter>,
    paramTypeKeys: List<TypeKey>,
    function: IrFunction,
    binding: Binding,
    graph: BindingGraph,
    thisReceiver: IrValueParameter,
    scopedFields: Map<TypeKey, IrField>,
    bindingStack: BindingStack,
  ): List<IrExpression> {
    return params.mapIndexed { i, param ->
      val typeKey = paramTypeKeys[i]
      // If it's in scoped fields, invoke that field
      val providerInstance =
        if (typeKey in scopedFields) {
          irGetField(irGet(thisReceiver), scopedFields.getValue(typeKey))
        } else {
          val entry =
            when (binding) {
              is Binding.ConstructorInjected -> {
                // TODO optimize lookup
                val constructor = binding.type.findInjectableConstructor()!!
                BindingStackEntry.injectedAt(typeKey, constructor, constructor.valueParameters[i])
              }
              is Binding.Provided -> {
                val function = binding.providerFunction
                BindingStackEntry.injectedAt(typeKey, function, function.valueParameters[i])
              }
              is Binding.ComponentDependency -> TODO()
            }
          bindingStack.push(entry)
          // Generate binding code for each param
          val binding = graph.getOrCreateBinding(typeKey, bindingStack)
          generateBindingCode(binding, graph, thisReceiver, scopedFields, bindingStack)
        }
      // TODO share logic from InjectConstructorTransformer
      if (param.isWrappedInLazy) {
        // DoubleCheck.lazy(...)
        irInvoke(
          dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
          callee = symbols.doubleCheckLazy,
          typeHint = param.typeName.wrapInLazy(symbols),
          providerInstance,
        )
      } else if (param.isLazyWrappedInProvider) {
        // ProviderOfLazy.create(provider)
        irInvoke(
          dispatchReceiver = irGetObject(symbols.providerOfLazyCompanionObject),
          callee = symbols.providerOfLazyCreate,
          args = arrayOf(providerInstance),
          typeHint = param.typeName.wrapInLazy(symbols).wrapInProvider(symbols.latticeProvider),
        )
      } else if (param.isWrappedInProvider) {
        providerInstance
      } else {
        irInvoke(
          dispatchReceiver = providerInstance,
          callee = symbols.providerInvoke,
          typeHint = param.typeName,
        )
      }
    }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun DeclarationIrBuilder.generateBindingCode(
    binding: Binding,
    graph: BindingGraph,
    thisReceiver: IrValueParameter,
    scopedFields: Map<TypeKey, IrField>,
    bindingStack: BindingStack,
  ): IrExpression =
    when (binding) {
      is Binding.ConstructorInjected -> {
        // Example_Factory.create(...)
        // TODO cache these constructor param lookups
        val injectableConstructor = binding.type.findInjectableConstructor()!!
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
        // Must use the injectable constructor's params for TypeKey as that has qualifier
        // annotations
        val paramTypeKeys =
          injectableConstructor.valueParameters.map { TypeKey.from(this@ComponentTransformer, it) }
        val params =
          createFunction.owner.valueParameters.mapToConstructorParameters(this@ComponentTransformer)
        val args =
          generateBindingArguments(
            params,
            paramTypeKeys,
            createFunction.owner,
            binding,
            graph,
            thisReceiver,
            scopedFields,
            bindingStack,
          )
        irInvoke(
          dispatchReceiver = irGetObject(creatorClass.symbol),
          callee = createFunction,
          args = args.toTypedArray(),
        )
      }

      is Binding.Provided -> {
        // TODO eventually generate factories for these, similar to modules
        // provider { this.provideFileSystem(...) }
        // TODO what about inherited/overridden providers?
        //  https://github.com/evant/kotlin-inject?tab=readme-ov-file#component-inheritance
        val receiver = thisReceiver
        val function = binding.providerFunction
        val params = function.valueParameters.mapToConstructorParameters(this@ComponentTransformer)
        val args =
          generateBindingArguments(
            params,
            params.map { it.typeKey },
            function,
            binding,
            graph,
            thisReceiver,
            scopedFields,
            bindingStack,
          )
        // This needs to be wrapped in a provider
        // TODO if it's a property with a field we could use InstanceFactory
        irInvoke(
          dispatchReceiver = null,
          callee = symbols.latticeProviderFunction,
          typeHint = context.irBuiltIns.getKFunctionType(function.returnType, emptyList()),
          irLambda(
            pluginContext,
            thisReceiver.parent, // TODO this is obvi wrong
            valueParameters = emptyList(),
            returnType = function.returnType,
            suspend = false,
          ) {
            +irReturn(
              irInvoke(
                dispatchReceiver = irGet(receiver),
                callee = function.symbol,
                args = args.toTypedArray(),
              )
            )
          },
        )
      }

      is Binding.ComponentDependency -> {
        // "return ${binding.component.className}.${binding.getter.name}()"
        TODO()
      }
    }
}
