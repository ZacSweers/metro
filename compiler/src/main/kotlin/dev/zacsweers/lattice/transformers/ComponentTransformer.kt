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
import dev.zacsweers.lattice.exitProcessing
import dev.zacsweers.lattice.ir.addCompanionObject
import dev.zacsweers.lattice.ir.addOverride
import dev.zacsweers.lattice.ir.allCallableMembers
import dev.zacsweers.lattice.ir.createIrBuilder
import dev.zacsweers.lattice.ir.doubleCheck
import dev.zacsweers.lattice.ir.getAllSuperTypes
import dev.zacsweers.lattice.ir.irInvoke
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.ir.rawType
import dev.zacsweers.lattice.letIf
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.addMember
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
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

    providesTransformer.visitComponentClass(declaration)

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

    val providerMethods =
      componentDeclaration
        .getAllSuperTypes(pluginContext, excludeSelf = false)
        .flatMap { it.classOrFail.owner.allCallableMembers() }
        // TODO is this enough for properties like @get:Provides
        .filter { function -> function.isAnnotatedWithAny(symbols.providesAnnotations) }
        // TODO validate
        .associateBy { TypeKey.from(this, it) }

    val exposedTypes =
      componentDeclaration
        .allCallableMembers()
        .filter { function ->
          // Abstract check is important. We leave alone any non-providers or overridden providers
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
        providerFunctions = providerMethods,
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
    component.providerFunctions.forEach { (typeKey, function) ->
      graph.addBinding(
        typeKey,
        Binding.Provided(
          function,
          typeKey,
          function.valueParameters.mapToConstructorParameters(this),
          function.scopeAnnotation(),
        ),
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
    val componentImplName = "${node.sourceComponent.name.asString()}Impl"
    return pluginContext.irFactory
      .buildClass { name = Name.identifier(componentImplName) }
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

        val componentTypeKey = TypeKey(node.sourceComponent.typeWith())

        // Add fields for this component and other instance params
        // TODO reevaluate later if this is necessary
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
              // DoubleCheck.provider(<provider>)
              initializer =
                pluginContext.createIrBuilder(symbol).run {
                  irExprBody(irGet(thisReceiverParameter))
                }
            }

        instanceFields[componentTypeKey] = thisComponentField
        // Add convenience mappings for all supertypes to this field so
        // instance providers from inherited types use this instance
        for (superType in node.sourceComponent.getAllSuperTypes(pluginContext)) {
          instanceFields[TypeKey(superType)] = thisComponentField
        }

        // TODO add fields for all unscoped providers used more than once in bindings

        // Add fields for providers. May include both scoped and unscoped providers
        val providerFields = mutableMapOf<TypeKey, IrField>()
        // Track used unscroped bindings. We only need to generate a field if they're used more than
        // once
        val usedUnscopedBindings = mutableSetOf<TypeKey>()
        val bindingDependencies = mutableMapOf<TypeKey, Map<TypeKey, Parameter>>()

        // Track a stack for bindings
        val bindingStack = BindingStack(node.sourceComponent)

        // TODO don't allow constructor params, only factories
        // TODO use InstanceFactory for bindsinstance
        // TODO need to collect all provided types too and create scoped fields if needed

        // First pass: collect bindings and their dependencies for ordering
        node.exposedTypes.forEach { (key, accessor) ->
          bindingStack.push(BindingStackEntry.requestedAt(key, accessor))
          val binding = graph.getOrCreateBinding(key, bindingStack)
          val bindingScope = binding.scope

          if (bindingScope != null) {
            if (node.scope == null || bindingScope != node.scope) {
              // Error if an unscoped component references scoped bindings
              val declarationToReport = node.sourceComponent
              bindingStack.push(BindingStackEntry.simpleTypeRef(key))
              val message = buildString {
                append(
                  "[Lattice/IncompatiblyScopedBindings] "
                )
                append(declarationToReport.kotlinFqName)
                append(" (unscoped) may not reference scoped bindings:")
                appendLine()
                appendBindingStack(bindingStack)
              }
              declarationToReport.reportError(message)

              exitProcessing()
            }
          }

          // Track dependencies before creating fields
          if (bindingScope != null) {
            // Scoped bindings always need fields
            bindingDependencies[key] = binding.dependencies
          } else {
            // Only add unscoped binding provider fields if they're used more than once
            if (key in usedUnscopedBindings) {
              bindingDependencies[key] = binding.dependencies
            } else {
              usedUnscopedBindings += key
            }
            // Check its dependencies too. Note we check parameters rather than the set of
            // dependencies
            // because this is what matters in terms of allocating fields
            for (param in binding.parameters) {
              val depKey = param.typeKey
              if (depKey in usedUnscopedBindings) {
                bindingStack.push(BindingStackEntry.requestedAt(depKey, accessor))
                bindingDependencies[depKey] =
                  graph.getOrCreateBinding(key, bindingStack).dependencies
              } else {
                usedUnscopedBindings += depKey
              }
            }
          }
        }

        // Compute safe initialization order
        val initOrder =
          bindingDependencies.keys.sortedWith { a, b ->
            when {
              // If b depends on a, a should be initialized first
              a in (bindingDependencies[b] ?: emptyMap()) -> -1
              // If a depends on b, b should be initialized first
              b in (bindingDependencies[a] ?: emptyMap()) -> 1
              // Otherwise order doesn't matter, fall back to just type order for idempotence
              else -> a.compareTo(b)
            }
          }

        // Create fields in dependency-order
        initOrder.forEach { key ->
          val binding = graph.getOrCreateBinding(key, BindingStack.empty())
          providerFields[key] =
            addField(
                fieldName = binding.nameHint.decapitalizeUS() + "Provider",
                fieldType = symbols.latticeProvider.typeWith(key.type),
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .apply {
                isFinal = true
                initializer =
                  pluginContext.createIrBuilder(symbol).run {
                    val provider =
                      generateBindingCode(
                          binding,
                          graph,
                          thisReceiverParameter,
                          instanceFields,
                          providerFields,
                          bindingStack,
                        )
                        .letIf(binding.scope != null) {
                          // If it's scoped, wrap it in double-check
                          // DoubleCheck.provider(<provider>)
                          it.doubleCheck(this@run, symbols)
                        }
                    irExprBody(provider)
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
            val property =
              function.correspondingPropertySymbol?.owner?.let { property ->
                addProperty { name = property.name }
              }
            val getter =
              property?.addGetter { returnType = function.returnType }
                ?: addOverride(function.kotlinFqName, function.name, key.type)
            getter.apply {
              this.dispatchReceiverParameter = thisReceiverParameter
              this.overriddenSymbols += function.symbol
              val binding = graph.getOrCreateBinding(key, BindingStack.empty())
              bindingStack.push(BindingStackEntry.requestedAt(key, function))
              body =
                pluginContext.createIrBuilder(symbol).run {
                  val receiver =
                    if (key in providerFields) {
                      irGetField(irGet(thisReceiverParameter), providerFields.getValue(key))
                    } else {
                      generateBindingCode(
                        binding,
                        graph,
                        thisReceiverParameter,
                        instanceFields,
                        providerFields,
                        bindingStack,
                      )
                    }
                  irExprBody(irInvoke(dispatchReceiver = receiver, callee = symbols.providerInvoke))
                }
            }
            bindingStack.pop()
          }
      }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun DeclarationIrBuilder.generateBindingArguments(
    paramTypeKeys: List<TypeKey>,
    function: IrFunction,
    binding: Binding,
    graph: BindingGraph,
    thisReceiver: IrValueParameter,
    instanceFields: Map<TypeKey, IrField>,
    providerFields: Map<TypeKey, IrField>,
    bindingStack: BindingStack,
  ): List<IrExpression> {
    val params = function.valueParameters.mapToConstructorParameters(this@ComponentTransformer)
    if (
      binding is Binding.Provided && binding.providerFunction.correspondingPropertySymbol == null
    ) {
      check(function.valueParameters.size == paramTypeKeys.size) {
        """
          Inconsistent parameter types!
          Input type keys:
            - ${paramTypeKeys.joinToString()}
          Binding parameters (${function.kotlinFqName}):
            - ${function.valueParameters.map { TypeKey.from(this@ComponentTransformer, it) }.joinToString()}
        """
          .trimIndent()
      }
    }
    return params.mapIndexed { i, param ->
      val typeKey = paramTypeKeys[i]
      instanceFields[typeKey]?.let { instanceField ->
        // If it's in instance field, invoke that field
        return@mapIndexed irGetField(irGet(thisReceiver), instanceField)
      }
      val providerInstance =
        if (typeKey in providerFields) {
          // If it's in provider fields, invoke that field
          irGetField(irGet(thisReceiver), providerFields.getValue(typeKey))
        } else {
          val entry =
            when (binding) {
              is Binding.ConstructorInjected -> {
                // TODO optimize lookup
                val constructor = binding.type.findInjectableConstructor()!!
                BindingStackEntry.injectedAt(typeKey, constructor, constructor.valueParameters[i])
              }
              is Binding.Provided -> {
                BindingStackEntry.injectedAt(typeKey, function, function.valueParameters[i])
              }
              is Binding.ComponentDependency -> TODO()
            }
          bindingStack.push(entry)
          // Generate binding code for each param
          val binding = graph.getOrCreateBinding(typeKey, bindingStack)
          generateBindingCode(
            binding,
            graph,
            thisReceiver,
            instanceFields,
            providerFields,
            bindingStack,
          )
        }
      // TODO share logic from InjectConstructorTransformer
      if (param.isWrappedInLazy) {
        // DoubleCheck.lazy(...)
        irInvoke(
          dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
          callee = symbols.doubleCheckLazy,
          typeHint = param.typeName.wrapInLazy(symbols),
          args = listOf(providerInstance),
        )
      } else if (param.isLazyWrappedInProvider) {
        // ProviderOfLazy.create(provider)
        irInvoke(
          dispatchReceiver = irGetObject(symbols.providerOfLazyCompanionObject),
          callee = symbols.providerOfLazyCreate,
          args = listOf(providerInstance),
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
    instanceFields: Map<TypeKey, IrField>,
    providerFields: Map<TypeKey, IrField>,
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
        val args =
          generateBindingArguments(
            paramTypeKeys,
            createFunction.owner,
            binding,
            graph,
            thisReceiver,
            instanceFields,
            providerFields,
            bindingStack,
          )
        irInvoke(
          dispatchReceiver = irGetObject(creatorClass.symbol),
          callee = createFunction,
          args = args,
        )
      }

      is Binding.Provided -> {
        // TODO what about inherited/overridden providers?
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
        val paramTypeKeys = buildList {
          // Can't use isStatic here because companion object functions actually have dispatch
          // receivers
          if (!binding.providerFunction.parentAsClass.isObject) {
            // The receiver param here will be the instance type
            add(
              TypeKey.from(
                this@ComponentTransformer,
                binding.providerFunction.dispatchReceiverParameter!!,
              )
            )
          }
          addAll(
            binding.providerFunction.valueParameters.map {
              TypeKey.from(this@ComponentTransformer, it)
            }
          )
        }
        val args =
          generateBindingArguments(
            paramTypeKeys,
            createFunction.owner,
            binding,
            graph,
            thisReceiver,
            instanceFields,
            providerFields,
            bindingStack,
          )
        irInvoke(
          dispatchReceiver = irGetObject(creatorClass.symbol),
          callee = createFunction,
          args = args,
        )
      }

      is Binding.ComponentDependency -> {
        // "return ${binding.component.className}.${binding.getter.name}()"
        TODO()
      }
    }
}
