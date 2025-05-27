// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.InjectConstructorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.ProvidesTransformer
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.proto.BindsCallableId
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.collections.mutableMapOf
import kotlin.collections.plus
import kotlin.collections.set
import org.jetbrains.kotlin.backend.common.lower.irThrow
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irConcat
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNotIs
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.addArgument
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.SymbolRemapper
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.ClassId

// TODO further refactor
//  move IR code gen out to IrGraphExpression?Generator
internal class IrGraphGenerator(
  metroContext: IrMetroContext,
  contributionData: IrContributionData,
  private val dependencyGraphNodesByClass: MutableMap<ClassId, DependencyGraphNode>,
  private val node: DependencyGraphNode,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
  private val parentTracer: Tracer,
  // TODO move these accesses to irAttributes
  private val providesTransformer: ProvidesTransformer,
  private val injectConstructorTransformer: InjectConstructorTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
) : IrMetroContext by metroContext {

  private val fieldNameAllocator = NameAllocator()
  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?
  // Fields for this graph and other instance params
  private val instanceFields = mutableMapOf<IrTypeKey, IrField>()

  // Fields for providers. May include both scoped and unscoped providers as well as bound
  // instances
  private val providerFields = mutableMapOf<IrTypeKey, IrField>()

  private val contributedGraphGenerator =
    IrContributedGraphGenerator(metroContext, contributionData, node.sourceGraph)

  fun generate() =
    with(graphClass) {
      val ctor = primaryConstructor!!

      val extraConstructorStatements = mutableListOf<IrBuilderWithScope.() -> IrStatement>()

      node.creator?.let { creator ->
        for ((i, param) in creator.parameters.regularParameters.withIndex()) {
          val isBindsInstance = param.isBindsInstance

          // TODO if we copy the annotations over in FIR we can skip this creator lookup all
          //  together
          val irParam = ctor.regularParameters[i]

          val addBoundInstanceField: (initializer: IrBuilderWithScope.() -> IrExpression) -> Unit =
            { initializer ->
              providerFields[param.typeKey] =
                addField(
                    fieldName =
                      fieldNameAllocator.newName(
                        "${param.name}".suffixIfNot("Instance").suffixIfNot("Provider")
                      ),
                    fieldType = symbols.metroProvider.typeWith(param.type),
                    fieldVisibility = DescriptorVisibilities.PRIVATE,
                  )
                  .apply {
                    isFinal = true
                    this.initializer =
                      pluginContext.createIrBuilder(symbol).run {
                        irExprBody(instanceFactory(param.type, initializer()))
                      }
                  }
            }
          if (isBindsInstance) {
            addBoundInstanceField { irGet(irParam) }
          } else {
            // It's a graph dep. Add all its accessors as available keys and point them at
            // this constructor parameter for provider field initialization
            val graphDep =
              node.includedGraphNodes[param.typeKey]
                ?: node.extendedGraphNodes[param.typeKey]
                ?: error("Undefined graph node ${param.typeKey}")
            val graphDepField =
              addSimpleInstanceField(
                fieldNameAllocator.newName(
                  graphDep.sourceGraph.name.asString().decapitalizeUS() + "Instance"
                ),
                graphDep.typeKey.type,
                { irGet(irParam) },
              )
            instanceFields[graphDep.typeKey] = graphDepField

            if (graphDep.isExtendable) {
              // Extended graphs
              addBoundInstanceField { irGet(irParam) }

              // Check that the input parameter is an instance of the metrograph class
              // Only do this for $$MetroGraph instances. Not necessary for ContributedGraphs
              if (graphDep.sourceGraph != graphClass) {
                val depMetroGraph = graphDep.sourceGraph.metroGraphOrFail
                extraConstructorStatements.add {
                  irIfThen(
                    condition = irNotIs(irGet(irParam), depMetroGraph.defaultType),
                    type = pluginContext.irBuiltIns.unitType,
                    thenPart =
                      irThrow(
                        irInvoke(
                          callee = context.irBuiltIns.illegalArgumentExceptionSymbol,
                          args =
                            listOf(
                              irConcat().apply {
                                addArgument(
                                  irString(
                                    "Constructor parameter ${irParam.name} _must_ be a Metro-compiler-generated instance of ${graphDep.sourceGraph.kotlinFqName.asString()} but was "
                                  )
                                )
                                addArgument(
                                  irInvoke(
                                    dispatchReceiver = irGet(irParam),
                                    callee = context.irBuiltIns.memberToString,
                                  )
                                )
                              }
                            ),
                        )
                      ),
                  )
                }
              }
            }
          }
        }
      }

      val thisReceiverParameter = thisReceiverOrFail
      val thisGraphField =
        addSimpleInstanceField(
          fieldNameAllocator.newName("thisGraphInstance"),
          node.typeKey.type,
          { irGet(thisReceiverParameter) },
        )

      instanceFields[node.typeKey] = thisGraphField

      // Expose the graph as a provider field
      providerFields[node.typeKey] =
        addField(
            fieldName =
              fieldNameAllocator.newName(
                node.sourceGraph.name.asString().decapitalizeUS().suffixIfNot("Provider")
              ),
            fieldType = symbols.metroProvider.typeWith(node.typeKey.type),
            fieldVisibility = DescriptorVisibilities.PRIVATE,
          )
          .apply {
            isFinal = true
            initializer =
              pluginContext.createIrBuilder(symbol).run {
                irExprBody(
                  instanceFactory(
                    node.typeKey.type,
                    irGetField(irGet(thisReceiverParameter), thisGraphField),
                  )
                )
              }
          }

      // Add instance fields for all the parent graphs
      for (parent in node.allExtendedNodes.values) {
        if (!parent.isExtendable) continue
        // DependencyGraphTransformer ensures this is generated by now
        val proto = parent.proto!!
        val parentMetroGraph = parent.sourceGraph.metroGraphOrFail
        val instanceAccessorNames = proto.instance_field_names.toSet()
        val instanceAccessors =
          parentMetroGraph.functions
            .filter {
              it.name.asString().removeSuffix(Symbols.StringNames.METRO_ACCESSOR) in
                instanceAccessorNames
            }
            .map {
              val metroFunction = metroFunctionOf(it)
              val contextKey = IrContextualTypeKey.from(metroContext, it)
              metroFunction to contextKey
            }
        for ((accessor, contextualTypeKey) in instanceAccessors) {
          instanceFields.getOrPut(contextualTypeKey.typeKey) {
            addField(
                fieldName =
                  fieldNameAllocator.newName(
                    contextualTypeKey.typeKey.type.rawType().name.asString().decapitalizeUS() +
                      "Instance"
                  ),
                fieldType = contextualTypeKey.typeKey.type,
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .apply {
                isFinal = true
                initializer =
                  pluginContext.createIrBuilder(symbol).run {
                    val receiverTypeKey =
                      accessor.ir.dispatchReceiverParameter!!
                        .type
                        .let {
                          val rawType = it.rawTypeOrNull()
                          // This stringy check is unfortunate but origins are not visible
                          // across compilation boundaries
                          if (rawType?.name == Symbols.Names.MetroGraph) {
                            // if it's a $$MetroGraph, we actually want the parent type
                            rawType.parentAsClass.defaultType
                          } else {
                            it
                          }
                        }
                        .let(::IrTypeKey)
                    irExprBody(
                      irInvoke(
                        dispatchReceiver =
                          irGetField(
                            irGet(thisReceiverParameter),
                            instanceFields.getValue(receiverTypeKey),
                          ),
                        callee = accessor.ir.symbol,
                        typeHint = accessor.ir.returnType,
                      )
                    )
                  }
              }
          }
        }
      }

      // Collect bindings and their dependencies for provider field ordering
      val initOrder =
        parentTracer.traceNested("Collect bindings") {
          val providerFieldBindings = ProviderFieldCollector(bindingGraph).collect()
          buildList(providerFieldBindings.size) {
            for (key in sealResult.sortedKeys) {
              providerFieldBindings[key]?.let(::add)
            }
          }
        }

      val baseGenerationContext = GraphGenerationContext(thisReceiverParameter)

      for ((key, binding) in bindingGraph.bindingsSnapshot()) {
        if (binding is Binding.GraphDependency) {

          val getter = binding.getter
          if (binding.isProviderFieldAccessor) {
            // Init a provider field pointing at this
            providerFields[key] =
              addField(
                  fieldName =
                    fieldNameAllocator.newName(
                      "${getter.name.asString().decapitalizeUS().removeSuffix(Symbols.StringNames.METRO_ACCESSOR)}Provider"
                    ),
                  fieldType = symbols.metroProvider.typeWith(node.typeKey.type),
                  fieldVisibility = DescriptorVisibilities.PRIVATE,
                )
                .apply {
                  isFinal = true
                  initializer =
                    pluginContext.createIrBuilder(symbol).run {
                      // If this is in instance fields, just do a quick assignment
                      val bindingExpression =
                        if (binding.typeKey in instanceFields) {
                          val field = instanceFields.getValue(binding.typeKey)
                          instanceFactory(
                            binding.typeKey.type,
                            irGetField(irGet(thisReceiverParameter), field),
                          )
                        } else {
                          generateBindingCode(binding, generationContext = baseGenerationContext)
                        }
                      irExprBody(bindingExpression)
                    }
                }
          }
        }
      }

      // For all deferred types, assign them first as factories
      // TODO For any types that depend on deferred types, they need providers too?
      @Suppress("UNCHECKED_CAST")
      val deferredFields: Map<IrTypeKey, IrField> =
        sealResult.deferredTypes.associateWith { deferredTypeKey ->
          val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
          val field =
            addField(
                fieldNameAllocator.newName(binding.nameHint.decapitalizeUS() + "Provider"),
                deferredTypeKey.type.wrapInProvider(symbols.metroProvider),
              )
              .apply {
                isFinal = true
                initializer =
                  pluginContext.createIrBuilder(symbol).run {
                    irExprBody(
                      irInvoke(
                        callee = symbols.metroDelegateFactoryConstructor,
                        typeArgs = listOf(deferredTypeKey.type),
                      )
                    )
                  }
              }

          providerFields[deferredTypeKey] = field
          field
        }

      // Create fields in dependency-order
      initOrder
        // Don't generate deferred types here, we'll generate them last
        .filterNot { it.typeKey in deferredFields }
        // Don't generate fields for anything already provided in provider fields (i.e. bound
        // instance types)
        .filterNot { it.typeKey in providerFields }
        .filterNot {
          // We don't generate fields for these even though we do track them in dependencies above,
          // it's just for propagating their aliased type in sorting
          it is Binding.Alias
        }
        .forEach { binding ->
          val key = binding.typeKey
          // Since assisted injections don't implement Factory, we can't just type these as
          // Provider<*> fields
          val fieldType =
            if (binding is Binding.ConstructorInjected && binding.isAssisted) {
              val factory =
                injectConstructorTransformer.getOrGenerateFactory(
                  binding.type,
                  binding.injectedConstructor,
                ) ?: return@forEach

              factory.factoryClass.typeWith() // TODO generic factories?
            } else {
              symbols.metroProvider.typeWith(key.type)
            }

          val field =
            addField(
                fieldName =
                  fieldNameAllocator.newName(
                    binding.nameHint.decapitalizeUS().suffixIfNot("Provider")
                  ),
                fieldType = fieldType,
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .apply {
                isFinal = true
                initializer =
                  pluginContext.createIrBuilder(symbol).run {
                    val provider =
                      generateBindingCode(binding, baseGenerationContext, fieldInitKey = key).letIf(
                        binding.scope != null
                      ) {
                        // If it's scoped, wrap it in double-check
                        // DoubleCheck.provider(<provider>)
                        it.doubleCheck(this@run, symbols, binding.typeKey)
                      }
                    irExprBody(provider)
                  }
              }
          providerFields[key] = field
        }

      // Add statements to our constructor's deferred fields _after_ we've added all provider
      // fields for everything else. This is important in case they reference each other
      for ((deferredTypeKey, field) in deferredFields) {
        val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
        extraConstructorStatements.add {
          irInvoke(
            dispatchReceiver = irGetObject(symbols.metroDelegateFactoryCompanion),
            callee = symbols.metroDelegateFactorySetDelegate,
            typeArgs = listOf(deferredTypeKey.type),
            // TODO de-dupe?
            args =
              listOf(
                irGetField(irGet(thisReceiverParameter), field),
                pluginContext.createIrBuilder(symbol).run {
                  generateBindingCode(
                      binding,
                      baseGenerationContext,
                      fieldInitKey = deferredTypeKey,
                    )
                    .letIf(binding.scope != null) {
                      // If it's scoped, wrap it in double-check
                      // DoubleCheck.provider(<provider>)
                      it.doubleCheck(this@run, symbols, binding.typeKey)
                    }
                },
              ),
          )
        }
      }

      // Add extra constructor statements
      with(ctor) {
        val originalBody = checkNotNull(body)
        buildBlockBody(pluginContext) {
          +originalBody.statements
          for (extra in extraConstructorStatements) {
            +extra()
          }
        }
      }

      parentTracer.traceNested("Implement overrides") { tracer ->
        node.implementOverrides(baseGenerationContext, tracer)
      }

      if (node.isExtendable) {
        parentTracer.traceNested("Generate Metro metadata") {
          // Finally, generate metadata
          val graphProto =
            node.toProto(
              bindingGraph = bindingGraph,
              supertypeClasses =
                node.allIncludedNodes
                  .filter { it.isExtendable }
                  .mapToSet { it.sourceGraph.classIdOrFail.asString() },
              parentGraphClasses =
                node.allExtendedNodes.values.mapToSet { it.sourceGraph.classIdOrFail.asString() },
              providerFields =
                providerFields
                  .filterKeys { typeKey -> typeKey != node.typeKey }
                  .filterKeys { typeKey ->
                    val binding = bindingGraph.requireBinding(typeKey, IrBindingStack.empty())
                    when {
                      // Don't re-expose existing accessors
                      binding is Binding.GraphDependency && binding.isProviderFieldAccessor -> false
                      // Only expose scoped bindings. Some provider fields may be for non-scoped
                      // bindings just for reuse. BoundInstance bindings still need to be passed on
                      binding.scope == null && binding !is Binding.BoundInstance -> false
                      else -> true
                    }
                  }
                  .values
                  .map { it.name.asString() }
                  .sorted(),
              instanceFields =
                instanceFields
                  .filterKeys { typeKey -> typeKey != node.typeKey }
                  .values
                  .map { it.name.asString() }
                  .sorted(),
            )
          val metroMetadata = MetroMetadata(METRO_VERSION, graphProto)

          writeDiagnostic({
            "graph-metadata-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
          }) {
            metroMetadata.toString()
          }

          // IR-generated types do not have metadata
          if (graphClass.origin !== Origins.ContributedGraph) {
            val serialized = MetroMetadata.ADAPTER.encode(metroMetadata)
            pluginContext.metadataDeclarationRegistrar.addCustomMetadataExtension(
              graphClass,
              PLUGIN_ID,
              serialized,
            )
          }
          dependencyGraphNodesByClass[node.sourceGraph.classIdOrFail]?.let { it.proto = graphProto }
        }

        // Expose getters for provider and instance fields and expose them to metadata
        sequence {
            for (entry in providerFields) {
              val binding = bindingGraph.requireBinding(entry.key, IrBindingStack.empty())
              if (binding is Binding.GraphDependency && binding.isProviderFieldAccessor) {
                // This'll get looked up directly by child graphs
                continue
              } else if (binding.scope == null && binding !is Binding.BoundInstance) {
                // Don't expose redundant accessors for unscoped bindings. BoundInstance bindings
                // still get passed on
                continue
              }
              yield(entry)
            }
            yieldAll(instanceFields.entries)
          }
          .filter {
            // Skip the graph instance field
            it.key != node.typeKey
          }
          .distinctBy {
            // Only generate once per field. Can happen for cases
            // where we add convenience keys for graph instance supertypes.
            it.value
          }
          .forEach { (key, field) ->
            val getter =
              addFunction(
                  name = "${field.name.asString()}${Symbols.StringNames.METRO_ACCESSOR}",
                  returnType = field.type,
                  visibility = DescriptorVisibilities.PUBLIC,
                  origin = Origins.InstanceFieldAccessor,
                )
                .apply {
                  key.qualifier?.let {
                    annotations +=
                      it.ir.transform(DeepCopyIrTreeWithSymbols(SymbolRemapper.EMPTY), null)
                        as IrConstructorCall
                  }
                  // Add Deprecated(HIDDEN) annotation to hide
                  annotations += hiddenDeprecated()
                  body =
                    pluginContext.createIrBuilder(symbol).run {
                      val expression =
                        if (key in instanceFields) {
                          irGetField(irGet(dispatchReceiverParameter!!), field)
                        } else {
                          val binding = bindingGraph.requireBinding(key, IrBindingStack.empty())
                          generateBindingCode(
                            binding,
                            baseGenerationContext.withReceiver(dispatchReceiverParameter!!),
                          )
                        }
                      irExprBodySafe(symbol, expression)
                    }
                }
            pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(getter)
          }
      }
    }

  private fun DependencyGraphNode.toProto(
    bindingGraph: IrBindingGraph,
    supertypeClasses: Set<String>,
    parentGraphClasses: Set<String>,
    providerFields: List<String>,
    instanceFields: List<String>,
  ): DependencyGraphProto {
    val bindsCallableIds =
      bindingGraph.bindingsSnapshot().values.filterIsInstance<Binding.Alias>().mapNotNull { binding
        ->
        binding.ir
          ?.overriddenSymbolsSequence()
          ?.lastOrNull()
          ?.owner
          ?.propertyIfAccessor
          ?.expectAsOrNull<IrDeclarationWithName>()
          ?.let {
            when (it) {
              is IrSimpleFunction -> {
                val callableId = it.callableId
                return@let BindsCallableId(
                  callableId.classId!!.asString(),
                  callableId.callableName.asString(),
                  is_property = false,
                )
              }
              is IrProperty -> {
                val callableId = it.callableId
                return@let BindsCallableId(
                  callableId.classId!!.asString(),
                  callableId.callableName.asString(),
                  is_property = true,
                )
              }
              else -> null
            }
          }
      }

    var multibindingAccessors = 0
    val accessorNames =
      accessors
        .sortedBy { it.first.ir.name.asString() }
        .onEachIndexed { index, (_, contextKey) ->
          val isMultibindingAccessor =
            bindingGraph.requireBinding(contextKey, IrBindingStack.empty()) is Binding.Multibinding
          if (isMultibindingAccessor) {
            multibindingAccessors = multibindingAccessors or (1 shl index)
          }
        }
        .map { it.first.ir.name.asString() }

    return DependencyGraphProto(
      is_graph = true,
      provider_field_names = providerFields,
      instance_field_names = instanceFields,
      provider_factory_classes =
        providerFactories.map { (_, factory) -> factory.clazz.classIdOrFail.asString() }.sorted(),
      binds_callable_ids =
        bindsCallableIds.sortedWith(
          compareBy<BindsCallableId> { it.class_id }
            .thenBy { it.callable_name }
            .thenBy { it.is_property }
        ),
      accessor_callable_names = accessorNames,
      included_classes = supertypeClasses.sorted(),
      parent_graph_classes = parentGraphClasses.sorted(),
      multibinding_accessor_indices = multibindingAccessors,
    )
  }

  // TODO add asProvider support?
  private fun IrClass.addSimpleInstanceField(
    name: String,
    type: IrType,
    initializerExpression: IrBuilderWithScope.() -> IrExpression,
  ): IrField =
    addField(fieldName = name, fieldType = type, fieldVisibility = DescriptorVisibilities.PRIVATE)
      .apply {
        isFinal = true
        initializer =
          pluginContext.createIrBuilder(symbol).run { irExprBody(initializerExpression()) }
      }

  private fun DependencyGraphNode.implementOverrides(
    context: GraphGenerationContext,
    parentTracer: Tracer,
  ) {
    // Implement abstract getters for accessors
    accessors.forEach { (function, contextualTypeKey) ->
      function.ir.apply {
        val declarationToFinalize =
          function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
        }
        val irFunction = this
        val binding = bindingGraph.requireBinding(contextualTypeKey, IrBindingStack.empty())
        body =
          pluginContext.createIrBuilder(symbol).run {
            if (binding is Binding.Multibinding) {
              // TODO if we have multiple accessors pointing at the same type, implement
              //  one and make the rest call that one. Not multibinding specific. Maybe
              //  groupBy { typekey }?
            }
            irExprBodySafe(
              symbol,
              typeAsProviderArgument(
                metroContext,
                contextualTypeKey,
                generateBindingCode(
                  binding,
                  context.withReceiver(irFunction.dispatchReceiverParameter!!),
                  contextualTypeKey,
                ),
                isAssisted = false,
                isGraphInstance = false,
              ),
            )
          }
      }
    }

    // Implement abstract injectors
    injectors.forEach { (overriddenFunction, typeKey) ->
      overriddenFunction.ir.apply {
        finalizeFakeOverride(context.thisReceiver)
        val targetParam = regularParameters[0]
        val binding =
          bindingGraph.requireBinding(typeKey, IrBindingStack.empty()) as Binding.MembersInjected

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
              val generatedInjector =
                membersInjectorTransformer.getOrGenerateInjector(clazz) ?: continue
              for ((function, parameters) in generatedInjector.injectFunctions) {
                +irInvoke(
                  dispatchReceiver = irGetObject(function.parentAsClass.symbol),
                  callee = function.symbol,
                  args =
                    buildList {
                      add(irGet(targetParam))
                      for (parameter in parameters.regularParameters) {
                        val paramBinding =
                          bindingGraph.requireBinding(
                            parameter.contextualTypeKey,
                            IrBindingStack.empty(),
                          )
                        add(
                          typeAsProviderArgument(
                            metroContext,
                            parameter.contextualTypeKey,
                            generateBindingCode(
                              paramBinding,
                              context.withReceiver(
                                overriddenFunction.ir.dispatchReceiverParameter!!
                              ),
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
    }

    // Implement no-op bodies for Binds providers
    bindsFunctions.forEach { (function, _) ->
      function.ir.apply {
        val declarationToFinalize =
          function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
        }
        body = stubExpressionBody(metroContext)
      }
    }

    // Implement bodies for contributed graphs
    // Sort by keys when generating so they have deterministic ordering
    contributedGraphs.entries
      .sortedBy { it.key }
      .forEach { (typeKey, function) ->
        function.ir.apply {
          val declarationToFinalize =
            function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
          if (declarationToFinalize.isFakeOverride) {
            declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
          }
          val irFunction = this
          val contributedGraph =
            getOrBuildContributedGraph(typeKey, sourceGraph, function, parentTracer)
          val ctor = contributedGraph.primaryConstructor!!
          body =
            pluginContext.createIrBuilder(symbol).run {
              irExprBodySafe(
                symbol,
                irCallConstructor(ctor.symbol, emptyList()).apply {
                  // First arg is always the graph instance
                  arguments[0] = irGet(irFunction.dispatchReceiverParameter!!)
                  for (i in 0 until regularParameters.size) {
                    arguments[i + 1] = irGet(irFunction.regularParameters[i])
                  }
                },
              )
            }
        }
      }
  }

  private fun getOrBuildContributedGraph(
    typeKey: IrTypeKey,
    parentGraph: IrClass,
    contributedAccessor: MetroSimpleFunction,
    parentTracer: Tracer,
  ): IrClass {
    val classId = typeKey.type.rawType().classIdOrFail
    return parentGraph.nestedClasses.firstOrNull { it.classId == classId }
      ?: run {
        // Find the function declaration in the original @ContributesGraphExtension.Factory
        val sourceFunction =
          contributedAccessor.ir
            .overriddenSymbolsSequence()
            .filter {
              it.owner.parentClassOrNull?.isAnnotatedWithAny(
                metroContext.symbols.classIds.contributesGraphExtensionFactoryAnnotations
              ) == true
            }
            .lastOrNull()
            ?.owner ?: contributedAccessor.ir

        val sourceFactory = sourceFunction.parentAsClass
        val sourceGraph = sourceFactory.parentAsClass
        parentTracer.traceNested("Generate contributed graph ${sourceGraph.name}") {
          contributedGraphGenerator.generateContributedGraph(
            sourceGraph = sourceGraph,
            sourceFactory = sourceFactory,
            factoryFunction = sourceFunction,
          )
        }
      }
  }

  private fun IrBuilderWithScope.generateBindingArguments(
    targetParams: Parameters<out Parameter>,
    function: IrFunction,
    binding: Binding,
    generationContext: GraphGenerationContext,
  ): List<IrExpression?> {
    val params = function.parameters(metroContext)
    // TODO only value args are supported atm
    val paramsToMap = buildList {
      if (
        binding is Binding.Provided &&
          targetParams.dispatchReceiverParameter?.type?.rawTypeOrNull()?.isObject != true
      ) {
        targetParams.dispatchReceiverParameter?.let(::add)
      }
      addAll(targetParams.regularParameters.filterNot { it.isAssisted })
    }
    if (
      binding is Binding.Provided &&
        binding.providerFactory.providesFunction.correspondingPropertySymbol == null
    ) {
      check(params.regularParameters.size == paramsToMap.size) {
        """
        Inconsistent parameter types for type ${binding.typeKey}!
        Input type keys:
          - ${paramsToMap.map { it.typeKey }.joinToString()}
        Binding parameters (${function.kotlinFqName}):
          - ${function.regularParameters.map { IrContextualTypeKey.from(metroContext,it).typeKey }.joinToString()}
        """
          .trimIndent()
      }
    }

    return params.regularParameters.mapIndexed { i, param ->
      val contextualTypeKey = paramsToMap[i].contextualTypeKey
      val typeKey = contextualTypeKey.typeKey

      val metroProviderSymbols = symbols.providerSymbolsFor(contextualTypeKey)

      // TODO consolidate this logic with generateBindingCode
      if (!contextualTypeKey.requiresProviderInstance) {
        // IFF the parameter can take a direct instance, try our instance fields
        instanceFields[typeKey]?.let { instanceField ->
          return@mapIndexed irGetField(irGet(generationContext.thisReceiver), instanceField).let {
            with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
          }
        }
      }

      val providerInstance =
        if (typeKey in providerFields) {
          // If it's in provider fields, invoke that field
          irGetField(irGet(generationContext.thisReceiver), providerFields.getValue(typeKey))
        } else {
          // Generate binding code for each param
          val paramBinding = bindingGraph.requireBinding(contextualTypeKey, IrBindingStack.empty())

          if (paramBinding is Binding.Absent) {
            // Null argument expressions get treated as absent in the final call
            return@mapIndexed null
          }

          generateBindingCode(
            paramBinding,
            generationContext,
            contextualTypeKey = param.contextualTypeKey,
          )
        }

      typeAsProviderArgument(
        metroContext,
        param.contextualTypeKey,
        providerInstance,
        isAssisted = param.isAssisted,
        isGraphInstance = param.isGraphInstance,
      )
    }
  }

  private fun generateMapKeyLiteral(binding: Binding): IrExpression {
    val mapKey =
      when (binding) {
        is Binding.Alias -> binding.annotations.mapKeys.first().ir
        is Binding.Provided -> binding.annotations.mapKeys.first().ir
        is Binding.ConstructorInjected -> binding.annotations.mapKeys.first().ir
        else -> error("Unsupported multibinding source: $binding")
      }

    val unwrapValue = shouldUnwrapMapKeyValues(mapKey)
    val expression =
      if (!unwrapValue) {
        mapKey
      } else {
        // We can just copy the expression!
        mapKey.arguments[0]!!.deepCopyWithSymbols()
      }

    return expression
  }

  private fun IrBuilderWithScope.generateBindingCode(
    binding: Binding,
    generationContext: GraphGenerationContext,
    contextualTypeKey: IrContextualTypeKey = binding.contextualTypeKey,
    fieldInitKey: IrTypeKey? = null,
  ): IrExpression {
    if (binding is Binding.Absent) {
      error(
        "Absent bindings need to be checked prior to generateBindingCode(). ${binding.typeKey} missing."
      )
    }

    val metroProviderSymbols = symbols.providerSymbolsFor(contextualTypeKey)

    // If we're initializing the field for this key, don't ever try to reach for an existing
    // provider for it.
    // This is important for cases like DelegateFactory and breaking cycles.
    if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
      providerFields[binding.typeKey]?.let {
        return irGetField(irGet(generationContext.thisReceiver), it).let {
          with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
        }
      }
    }

    return when (binding) {
      is Binding.ConstructorInjected -> {
        // Example_Factory.create(...)
        val injectableConstructor = binding.injectedConstructor
        val factory =
          injectConstructorTransformer.getOrGenerateFactory(binding.type, injectableConstructor)
            ?: return stubExpression(metroContext)

        with(factory) {
          invokeCreateExpression { createFunction ->
            generateBindingArguments(
              createFunction.parameters(metroContext),
              createFunction,
              binding,
              generationContext,
            )
          }
        }
      }

      is Binding.ObjectClass -> {
        instanceFactory(binding.typeKey.type, irGetObject(binding.type.symbol))
      }

      is Binding.Alias -> {
        // For binds functions, just use the backing type
        val aliasedBinding = binding.aliasedBinding(bindingGraph, IrBindingStack.empty())
        check(aliasedBinding != binding) { "Aliased binding aliases itself" }
        return generateBindingCode(aliasedBinding, generationContext)
      }

      is Binding.Provided -> {
        val factoryClass =
          providesTransformer.getOrLookupFactoryClass(binding)?.clazz
            ?: return stubExpression(metroContext)
        // Invoke its factory's create() function
        val creatorClass =
          if (factoryClass.isObject) {
            factoryClass
          } else {
            factoryClass.companionObject()!!
          }
        val createFunction = creatorClass.requireSimpleFunction(Symbols.StringNames.CREATE)
        // Must use the provider's params for IrTypeKey as that has qualifier
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
        val implClass =
          assistedFactoryTransformer.getOrGenerateImplClass(binding.type)
            ?: return stubExpression(metroContext)

        val dispatchReceiver: IrExpression?
        val createFunction: IrSimpleFunctionSymbol
        val isFromDagger: Boolean
        if (options.enableDaggerRuntimeInterop && implClass.isFromJava()) {
          // Dagger interop
          createFunction =
            implClass
              .simpleFunctions()
              .first {
                it.isStatic &&
                  (it.name == Symbols.Names.create ||
                    it.name == Symbols.Names.createFactoryProvider)
              }
              .symbol
          dispatchReceiver = null
          isFromDagger = true
        } else {
          val implClassCompanion = implClass.companionObject()!!
          createFunction = implClassCompanion.requireSimpleFunction(Symbols.StringNames.CREATE)
          dispatchReceiver = irGetObject(implClassCompanion.symbol)
          isFromDagger = false
        }

        val targetBinding =
          bindingGraph.requireBinding(binding.target.typeKey, IrBindingStack.empty())
        val delegateFactoryProvider = generateBindingCode(targetBinding, generationContext)
        val invokeCreateExpression =
          irInvoke(
            dispatchReceiver = dispatchReceiver,
            callee = createFunction,
            args = listOf(delegateFactoryProvider),
          )
        if (isFromDagger) {
          with(symbols.daggerSymbols) {
            val targetType =
              (createFunction.owner.returnType as IrSimpleType).arguments[0].typeOrFail
            transformToMetroProvider(invokeCreateExpression, targetType)
          }
        } else {
          invokeCreateExpression
        }
      }

      is Binding.Multibinding -> {
        generateMultibindingExpression(binding, contextualTypeKey, generationContext, fieldInitKey)
      }

      is Binding.MembersInjected -> {
        val injectedClass = pluginContext.referenceClass(binding.targetClassId)!!.owner
        val injectedType = injectedClass.defaultType
        val injectorClass = membersInjectorTransformer.getOrGenerateInjector(injectedClass)?.ir

        if (injectorClass == null) {
          // Return a noop
          irInvoke(
              dispatchReceiver = irGetObject(symbols.metroMembersInjectors),
              callee = symbols.metroMembersInjectorsNoOp,
              typeArgs = listOf(injectedType),
            )
            .let { with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) } }
        } else {
          val createFunction = injectorClass.requireSimpleFunction(Symbols.StringNames.CREATE)
          val args =
            generateBindingArguments(
              binding.parameters,
              createFunction.owner,
              binding,
              generationContext,
            )
          instanceFactory(
              injectedType,
              // InjectableClass_MembersInjector.create(stringValueProvider,
              // exampleComponentProvider)
              irInvoke(
                dispatchReceiver =
                  if (injectorClass.isObject) {
                    irGetObject(injectorClass.symbol)
                  } else {
                    // It's static from java, dagger interop
                    check(createFunction.owner.isStatic)
                    null
                  },
                callee = createFunction,
                args = args,
              ),
            )
            .let { with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) } }
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
        val ownerKey = IrTypeKey(binding.graph.defaultType)
        val graphInstanceField =
          instanceFields[ownerKey]
            ?: run {
              error(
                "No matching included type instance found for type ${ownerKey}. Available instance fields ${instanceFields.keys}"
              )
            }

        val getterContextKey = IrContextualTypeKey.from(metroContext, binding.getter)

        val invokeGetter =
          irInvoke(
            dispatchReceiver =
              irGetField(irGet(generationContext.thisReceiver), graphInstanceField),
            callee = binding.getter.symbol,
            typeHint = binding.typeKey.type,
          )

        if (getterContextKey.isLazyWrappedInProvider) {
          binding.getter.reportError("Provider<Lazy<T>> accessors are not supported.")
          exitProcessing()
        } else if (getterContextKey.isWrappedInProvider) {
          // It's already a provider
          invokeGetter
        } else {
          val lambda =
            irLambda(
              context = pluginContext,
              parent = this.parent,
              receiverParameter = null,
              emptyList(),
              binding.typeKey.type,
              suspend = false,
            ) {
              val returnExpression =
                if (getterContextKey.isWrappedInLazy) {
                  irInvoke(invokeGetter, callee = symbols.lazyGetValue)
                } else {
                  invokeGetter
                }
              +irReturn(returnExpression)
            }
          irInvoke(
            dispatchReceiver = null,
            callee = symbols.metroProviderFunction,
            typeHint = binding.typeKey.type.wrapInProvider(symbols.metroProvider),
            typeArgs = listOf(binding.typeKey.type),
            args = listOf(lambda),
          )
        }
      }
    }
  }

  private fun IrBuilderWithScope.generateMultibindingExpression(
    binding: Binding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    return if (binding.isSet) {
      generateSetMultibindingExpression(binding, contextualTypeKey, generationContext, fieldInitKey)
    } else {
      // It's a map
      generateMapMultibindingExpression(binding, contextualTypeKey, generationContext, fieldInitKey)
    }
  }

  private fun IrBuilderWithScope.generateSetMultibindingExpression(
    binding: Binding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val elementType = (binding.typeKey.type as IrSimpleType).arguments.single().typeOrFail
    val (collectionProviders, individualProviders) =
      binding.sourceBindings
        .map {
          bindingGraph
            .requireBinding(it, IrBindingStack.empty())
            .expectAs<Binding.BindingWithAnnotations>()
        }
        .partition { it.annotations.isElementsIntoSet }
    // If we have any @ElementsIntoSet, we need to use SetFactory
    return if (collectionProviders.isNotEmpty() || contextualTypeKey.requiresProviderInstance) {
      generateSetFactoryExpression(
        elementType,
        collectionProviders,
        individualProviders,
        generationContext,
        fieldInitKey,
      )
    } else {
      generateSetBuilderExpression(binding, elementType, generationContext, fieldInitKey)
    }
  }

  private fun IrBuilderWithScope.generateSetBuilderExpression(
    binding: Binding.Multibinding,
    elementType: IrType,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val callee: IrSimpleFunctionSymbol
    val args: List<IrExpression>
    when (val size = binding.sourceBindings.size) {
      0 -> {
        // emptySet()
        callee = symbols.emptySet
        args = emptyList()
      }

      1 -> {
        // setOf(<one>)
        callee = symbols.setOfSingleton
        val provider =
          binding.sourceBindings.first().let {
            bindingGraph.requireBinding(it, IrBindingStack.empty())
          }
        args = listOf(generateMultibindingArgument(provider, generationContext, fieldInitKey))
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
              receiverParameter = pluginContext.irBuiltIns.mutableSetClass.typeWith(elementType),
              valueParameters = emptyList(),
              returnType = pluginContext.irBuiltIns.unitType,
              suspend = false,
            ) { function ->
              // This is the mutable set receiver
              val functionReceiver = function.extensionReceiverParameterCompat!!
              binding.sourceBindings
                .map { bindingGraph.requireBinding(it, IrBindingStack.empty()) }
                .forEach { provider ->
                  +irInvoke(
                    dispatchReceiver = irGet(functionReceiver),
                    callee = symbols.mutableSetAdd.symbol,
                    args =
                      listOf(
                        generateMultibindingArgument(provider, generationContext, fieldInitKey)
                      ),
                  )
                }
            }
          )
        }
      }
    }

    return irCall(callee = callee, type = binding.typeKey.type, typeArguments = listOf(elementType))
      .apply {
        for ((i, arg) in args.withIndex()) {
          arguments[i] = arg
        }
      }
  }

  private fun IrBuilderWithScope.generateSetFactoryExpression(
    elementType: IrType,
    collectionProviders: List<Binding>,
    individualProviders: List<Binding>,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
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
        typeArgs = listOf(elementType),
        args = listOf(irInt(individualProviders.size), irInt(collectionProviders.size)),
      )

    val withProviders =
      individualProviders.fold(builder) { receiver, provider ->
        irInvoke(
          dispatchReceiver = receiver,
          callee = symbols.setFactoryBuilderAddProviderFunction,
          typeHint = builder.type,
          args =
            listOf(generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey)),
        )
      }

    // .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
    val withCollectionProviders =
      collectionProviders.fold(withProviders) { receiver, provider ->
        irInvoke(
          dispatchReceiver = receiver,
          callee = symbols.setFactoryBuilderAddCollectionProviderFunction,
          typeHint = builder.type,
          args =
            listOf(generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey)),
        )
      }

    // .build()
    return irInvoke(
      dispatchReceiver = withCollectionProviders,
      callee = symbols.setFactoryBuilderBuildFunction,
      typeHint =
        pluginContext.irBuiltIns.setClass
          .typeWith(elementType)
          .wrapInProvider(symbols.metroProvider),
    )
  }

  private fun IrBuilderWithScope.generateMapMultibindingExpression(
    binding: Binding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    // MapFactory.<Integer, Integer>builder(2)
    //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
    //   .put(2, provideMapInt2Provider)
    //   .build()
    // MapProviderFactory.<Integer, Integer>builder(2)
    //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
    //   .put(2, provideMapInt2Provider)
    //   .build()
    val valueWrappedType = contextualTypeKey.wrappedType.findMapValueType()!!

    val mapTypeArgs = (contextualTypeKey.typeKey.type as IrSimpleType).arguments
    check(mapTypeArgs.size == 2) { "Unexpected map type args: ${mapTypeArgs.joinToString()}" }
    val keyType: IrType = mapTypeArgs[0].typeOrFail
    val rawValueType = mapTypeArgs[1].typeOrFail
    val rawValueTypeMetadata =
      rawValueType.typeOrFail.asContextualTypeKey(metroContext, null, hasDefault = false)

    // TODO what about Map<String, Provider<Lazy<String>>>?
    //  isDeferrable() but we need to be able to convert back to the middle type
    val useProviderFactory: Boolean = valueWrappedType is WrappedType.Provider
    val valueType: IrType = rawValueTypeMetadata.typeKey.type

    val targetCompanionObject =
      if (useProviderFactory) {
        symbols.mapProviderFactoryCompanionObject
      } else {
        symbols.mapFactoryCompanionObject
      }

    val size = binding.sourceBindings.size
    val mapProviderType =
      pluginContext.irBuiltIns.mapClass
        .typeWith(
          keyType,
          if (useProviderFactory) {
            rawValueType.wrapInProvider(symbols.metroProvider)
          } else {
            rawValueType
          },
        )
        .wrapInProvider(symbols.metroProvider)

    // TODO check if binding allows empty?
    if (size == 0) {
      // If it's empty then short-circuit here
      val emptyCallee =
        if (useProviderFactory) {
          // MapProviderFactory.empty()
          symbols.mapProviderFactoryEmptyFunction
        } else {
          // MapFactory.empty()
          symbols.mapFactoryEmptyFunction
        }

      return irInvoke(
        dispatchReceiver = irGetObject(targetCompanionObject),
        callee = emptyCallee,
        typeHint = mapProviderType,
      )
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
        typeArgs = listOf(keyType, valueType),
        typeHint = builderType.typeWith(keyType, valueType),
        args = listOf(irInt(size)),
      )

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
      binding.sourceBindings
        .map { bindingGraph.requireBinding(it, IrBindingStack.empty()) }
        .fold(builder) { receiver, sourceBinding ->
          val providerTypeMetadata = sourceBinding.contextualTypeKey

          // TODO FIR this should be an error actually
          val isMap =
            providerTypeMetadata.typeKey.type.rawType().symbol == context.irBuiltIns.mapClass

          val putter =
            if (isMap) {
              // use putAllFunction
              // .putAll(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
              // TODO is this only for inheriting in GraphExtensions?
              TODO("putAll isn't yet supported")
            } else {
              // .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
              putFunction
            }
          irInvoke(
            dispatchReceiver = receiver,
            callee = putter,
            typeHint = builder.type,
            args =
              listOf(
                generateMapKeyLiteral(sourceBinding),
                generateBindingCode(sourceBinding, generationContext, fieldInitKey = fieldInitKey),
              ),
          )
        }

    // .build()
    val buildFunction =
      if (useProviderFactory) {
        symbols.mapProviderFactoryBuilderBuildFunction
      } else {
        symbols.mapFactoryBuilderBuildFunction
      }

    return irInvoke(
      dispatchReceiver = withProviders,
      callee = buildFunction,
      typeHint = mapProviderType,
    )
  }

  private fun IrBuilderWithScope.generateMultibindingArgument(
    provider: Binding,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val bindingCode = generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey)
    return typeAsProviderArgument(
      metroContext,
      contextKey = IrContextualTypeKey.create(provider.typeKey),
      bindingCode = bindingCode,
      isAssisted = false,
      isGraphInstance = false,
    )
  }
}

internal class GraphGenerationContext(val thisReceiver: IrValueParameter) {
  // Each declaration in FIR is actually generated with a different "this" receiver, so we
  // need to be able to specify this per-context.
  // TODO not sure if this is really the best way to do this? Only necessary when implementing
  //  accessors/injectors
  fun withReceiver(receiver: IrValueParameter): GraphGenerationContext =
    GraphGenerationContext(receiver)
}
