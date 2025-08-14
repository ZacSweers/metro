// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.SymbolRemapper
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal typealias FieldInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

// Borrowed from Dagger
// https://github.com/google/dagger/blob/b39cf2d0640e4b24338dd290cb1cb2e923d38cb3/dagger-compiler/main/java/dagger/internal/codegen/writing/ComponentImplementation.java#L263
private const val STATEMENTS_PER_METHOD = 25

// TODO further refactor
//  move IR code gen out to IrGraphExpression?Generator
internal class IrGraphGenerator(
  metroContext: IrMetroContext,
  private val dependencyGraphNodesByClass: (ClassId) -> DependencyGraphNode?,
  private val node: DependencyGraphNode,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
  private val parentTracer: Tracer,
  // TODO move these accesses to irAttributes
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val contributedGraphGenerator: IrGraphExtensionGenerator,
) : IrMetroContext by metroContext {

  private val fieldNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val functionNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val nestedClassNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)

  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?
  // Fields for this graph and other instance params
  private val instanceFields = mutableMapOf<IrTypeKey, IrField>()

  // Fields for providers. May include both scoped and unscoped providers as well as bound
  // instances
  private val providerFields = LinkedHashMap<IrTypeKey, IrField>()

  /**
   * To avoid `MethodTooLargeException`, we split field initializations up over multiple constructor
   * inits.
   *
   * @see <a href="https://github.com/ZacSweers/metro/issues/645">#645</a>
   */
  private val fieldInitializers = mutableListOf<Pair<IrField, FieldInitializer>>()
  private val fieldsToTypeKeys = mutableMapOf<IrField, IrTypeKey>()

  fun IrField.withInit(typeKey: IrTypeKey, init: FieldInitializer): IrField = apply {
    fieldsToTypeKeys[this] = typeKey
    fieldInitializers += (this to init)
  }

  fun IrField.initFinal(body: IrBuilderWithScope.() -> IrExpression): IrField = apply {
    isFinal = true
    initializer = createIrBuilder(symbol).run { irExprBody(body()) }
  }

  fun generate() =
    with(graphClass) {
      val ctor = primaryConstructor!!

      val constructorStatements =
        mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      val initStatements =
        mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      val thisReceiverParameter = thisReceiverOrFail

      fun addBoundInstanceField(
        typeKey: IrTypeKey,
        name: Name,
        initializer:
          IrBuilderWithScope.(thisReceiver: IrValueParameter, typeKey: IrTypeKey) -> IrExpression,
      ) {
        // Don't add it if it's not used
        if (typeKey !in sealResult.reachableKeys) return

        providerFields[typeKey] =
          addField(
              fieldName =
                fieldNameAllocator.newName(
                  name
                    .asString()
                    .removePrefix("$$")
                    .decapitalizeUS()
                    .suffixIfNot("Instance")
                    .suffixIfNot("Provider")
                ),
              fieldType = symbols.metroProvider.typeWith(typeKey.type),
              fieldVisibility = DescriptorVisibilities.PRIVATE,
            )
            .initFinal {
              instanceFactory(typeKey.type, initializer(thisReceiverParameter, typeKey))
            }
      }

      node.creator?.let { creator ->
        for ((i, param) in creator.parameters.regularParameters.withIndex()) {
          val isBindsInstance = param.isBindsInstance

          // TODO if we copy the annotations over in FIR we can skip this creator lookup all
          //  together
          val irParam = ctor.regularParameters[i]

          if (isBindsInstance || creator.bindingContainersParameterIndices.isSet(i)) {
            addBoundInstanceField(param.typeKey, param.name) { _, _ -> irGet(irParam) }
          } else {
            // It's a graph dep. Add all its accessors as available keys and point them at
            // this constructor parameter for provider field initialization
            val graphDep =
              node.includedGraphNodes[param.typeKey]
                ?: node.extendedGraphNodes[param.typeKey]
                ?: error("Undefined graph node ${param.typeKey}")

            // Don't add it if it's not used
            if (param.typeKey !in sealResult.reachableKeys) continue

            val graphDepField =
              addSimpleInstanceField(
                fieldNameAllocator.newName(graphDep.sourceGraph.name.asString() + "Instance"),
                param.typeKey,
              ) {
                irGet(irParam)
              }
            // Link both the graph typekey and the (possibly-impl type)
            instanceFields[param.typeKey] = graphDepField
            instanceFields[graphDep.typeKey] = graphDepField

            if (graphDep.hasExtensions) {
              val depMetroGraph = graphDep.sourceGraph.metroGraphOrFail
              val paramName = depMetroGraph.sourceGraphIfMetroGraph.name
              addBoundInstanceField(param.typeKey, paramName) { _, _ -> irGet(irParam) }
            }
          }
        }
      }

      // Create managed binding containers instance fields if used
      val allBindingContainers = buildSet {
        addAll(node.bindingContainers)
        addAll(node.allExtendedNodes.values.flatMap { it.bindingContainers })
      }
      allBindingContainers
        .sortedBy { it.kotlinFqName.asString() }
        .forEach { clazz ->
          addBoundInstanceField(IrTypeKey(clazz), clazz.name) { _, _ ->
            irCallConstructor(clazz.primaryConstructor!!.symbol, emptyList())
          }
        }

      // Don't add it if it's not used
      if (node.typeKey in sealResult.reachableKeys) {
        val thisGraphField =
          addSimpleInstanceField(fieldNameAllocator.newName("thisGraphInstance"), node.typeKey) {
            irGet(thisReceiverParameter)
          }

        instanceFields[node.typeKey] = thisGraphField

        // Expose the graph as a provider field
        // TODO this isn't always actually needed but different than the instance field above
        //  would be nice if we could determine if this field is unneeded
        providerFields[node.typeKey] =
          addField(
              fieldName = fieldNameAllocator.newName("thisGraphInstanceProvider"),
              fieldType = symbols.metroProvider.typeWith(node.typeKey.type),
              fieldVisibility = DescriptorVisibilities.PRIVATE,
            )
            .initFinal {
              instanceFactory(
                node.typeKey.type,
                irGetField(irGet(thisReceiverParameter), thisGraphField),
              )
            }
      }

      // Add instance fields for all the parent graphs
      for (parent in node.allExtendedNodes.values) {
        // TODO make this an error?
        if (!parent.hasExtensions) continue
        val parentMetroGraph = parent.sourceGraph.metroGraphOrFail
        val instanceAccessors =
          parentMetroGraph.functions
            .filter {
              val metroAccessor =
                it.getAnnotation(Symbols.FqNames.MetroAccessor) ?: return@filter false
              // This has a single "isInstanceAccessor" property
              metroAccessor.getSingleConstBooleanArgumentOrNull() == true
            }
            .mapNotNull {
              val contextKey = IrContextualTypeKey.from(it)

              if (
                contextKey.typeKey == node.originalTypeKey ||
                  contextKey.typeKey == node.creator?.typeKey
              ) {
                // Accessor of this graph extension or its factory, no need to include these
                return@mapNotNull null
              }

              val metroFunction = metroFunctionOf(it)
              metroFunction to contextKey
            }

        for ((accessor, contextualTypeKey) in instanceAccessors) {
          // If this isn't extended and this type key isn't used, ignore it
          if (!node.hasExtensions && contextualTypeKey.typeKey !in sealResult.reachableKeys) {
            continue
          }

          val typeKey = contextualTypeKey.typeKey
          instanceFields.getOrPut(typeKey) {
            addField(
                fieldName =
                  fieldNameAllocator.newName(
                    typeKey.type.rawType().name.asString().decapitalizeUS() + "Instance"
                  ),
                fieldType = typeKey.type,
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .withInit(typeKey) { thisReceiver, _ ->
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
                    .let(IrTypeKey.Companion::invoke)
                irInvoke(
                  dispatchReceiver =
                    irGetField(
                      irGet(thisReceiver),
                      instanceFields[receiverTypeKey]
                        ?: error(
                          "Receiver type key $receiverTypeKey not found for binding $accessor"
                        ),
                    ),
                  callee = accessor.ir.symbol,
                  typeHint = accessor.ir.returnType,
                )
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
              if (key in sealResult.reachableKeys) {
                providerFieldBindings[key]?.let(::add)
              }
            }
          }
        }

      val baseGenerationContext = GraphGenerationContext(thisReceiverParameter)

      // TODO can we consolidate this with regular provider field collection?
      for ((key, binding) in bindingGraph.bindingsSnapshot()) {
        if (binding is IrBinding.GraphDependency && key in sealResult.reachableKeys) {
          if (binding.isProviderFieldAccessor) {
            // Init a provider field pointing at this
            providerFields[key] =
              addField(
                  fieldName =
                    fieldNameAllocator.newName(
                      buildString {
                        append(key.type.rawType().name.asString().decapitalizeUS())
                        append("Provider")
                      }
                    ),
                  fieldType = symbols.metroProvider.typeWith(key.type),
                  fieldVisibility = DescriptorVisibilities.PRIVATE,
                )
                .apply { key.qualifier?.let { annotations += it.ir.deepCopyWithSymbols() } }
                .withInit(key) { thisReceiver, _ ->
                  // If this is in instance fields, just do a quick assignment
                  if (binding.typeKey in instanceFields) {
                    val field = instanceFields.getValue(binding.typeKey)
                    instanceFactory(binding.typeKey.type, irGetField(irGet(thisReceiver), field))
                  } else {
                    generateBindingCode(
                      binding = binding,
                      generationContext = baseGenerationContext.withReceiver(thisReceiver),
                      fieldInitKey = key,
                    )
                  }
                }
          }
        }
        if (binding is IrBinding.GraphExtension && binding.isScoped()) {
          // Init a provider field pointing at this
          providerFields[key] =
            addField(
                fieldName =
                  fieldNameAllocator.newName(
                    buildString {
                      append(key.type.rawType().name.asString().decapitalizeUS())
                      append("Provider")
                    }
                  ),
                fieldType = symbols.metroProvider.typeWith(key.type),
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .apply { key.qualifier?.let { annotations += it.ir.deepCopyWithSymbols() } }
              .withInit(key) { thisReceiver, _ ->
                generateBindingCode(
                    binding = binding,
                    generationContext = baseGenerationContext.withReceiver(thisReceiver),
                    fieldInitKey = key,
                  )
                  .doubleCheck(this@withInit, symbols, key)
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
              .withInit(binding.typeKey) { _, _ ->
                irInvoke(
                  callee = symbols.metroDelegateFactoryConstructor,
                  typeArgs = listOf(deferredTypeKey.type),
                )
              }

          providerFields[deferredTypeKey] = field
          field
        }

      // Create fields in dependency-order
      initOrder
        .asSequence()
        .filterNot {
          // Don't generate deferred types here, we'll generate them last
          it.typeKey in deferredFields ||
            // Don't generate fields for anything already provided in provider/instance fields (i.e.
            // bound instance types)
            it.typeKey in instanceFields ||
            it.typeKey in providerFields ||
            // We don't generate fields for these even though we do track them in dependencies
            // above, it's just for propagating their aliased type in sorting
            it is IrBinding.Alias
        }
        .toList()
        .also { fieldBindings ->
          writeDiagnostic("keys-providerFields-${parentTracer.tag}.txt") {
            fieldBindings.joinToString("\n") { it.typeKey.toString() }
          }
          writeDiagnostic("keys-scopedProviderFields-${parentTracer.tag}.txt") {
            fieldBindings.filter { it.isScoped() }.joinToString("\n") { it.typeKey.toString() }
          }
        }
        .forEach { binding ->
          val key = binding.typeKey
          // Since assisted and member injections don't implement Factory, we can't just type these
          // as Provider<*> fields
          var isProviderType = true
          val suffix: String
          val fieldType =
            when (binding) {
              is IrBinding.ConstructorInjected if binding.isAssisted -> {
                isProviderType = false
                suffix = "Factory"
                binding.classFactory.factoryClass.typeWith() // TODO generic factories?
              }
              else -> {
                suffix = "Provider"
                symbols.metroProvider.typeWith(key.type)
              }
            }

          val field =
            addField(
                fieldName =
                  fieldNameAllocator.newName(binding.nameHint.decapitalizeUS().suffixIfNot(suffix)),
                fieldType = fieldType,
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .withInit(key) { thisReceiver, typeKey ->
                generateBindingCode(
                    binding,
                    baseGenerationContext.withReceiver(thisReceiver),
                    fieldInitKey = typeKey,
                  )
                  .letIf(binding.scope != null && isProviderType) {
                    // If it's scoped, wrap it in double-check
                    // DoubleCheck.provider(<provider>)
                    it.doubleCheck(this@withInit, symbols, binding.typeKey)
                  }
              }
          providerFields[key] = field
        }

      // Add statements to our constructor's deferred fields _after_ we've added all provider
      // fields for everything else. This is important in case they reference each other
      for ((deferredTypeKey, field) in deferredFields) {
        val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
        initStatements.add { thisReceiver ->
          irInvoke(
            dispatchReceiver = irGetObject(symbols.metroDelegateFactoryCompanion),
            callee = symbols.metroDelegateFactorySetDelegate,
            typeArgs = listOf(deferredTypeKey.type),
            // TODO de-dupe?
            args =
              listOf(
                irGetField(irGet(thisReceiver), field),
                createIrBuilder(symbol).run {
                  generateBindingCode(
                      binding,
                      baseGenerationContext.withReceiver(thisReceiver),
                      fieldInitKey = deferredTypeKey,
                    )
                    .letIf(binding.isScoped()) {
                      // If it's scoped, wrap it in double-check
                      // DoubleCheck.provider(<provider>)
                      it.doubleCheck(this@run, symbols, binding.typeKey)
                    }
                },
              ),
          )
        }
      }

      if (
        options.chunkFieldInits &&
          fieldInitializers.size + initStatements.size > STATEMENTS_PER_METHOD
      ) {
        // Larger graph, split statements
        // Chunk our constructor statements and split across multiple init functions
        val chunks =
          buildList<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement> {
              // Add field initializers first
              for ((field, init) in fieldInitializers) {
                add { thisReceiver ->
                  irSetField(
                    irGet(thisReceiver),
                    field,
                    init(thisReceiver, fieldsToTypeKeys.getValue(field)),
                  )
                }
              }
              for (statement in initStatements) {
                add { thisReceiver -> statement(thisReceiver) }
              }
            }
            .chunked(STATEMENTS_PER_METHOD)

        val initFunctionsToCall =
          chunks.map { statementsChunk ->
            val initName = functionNameAllocator.newName("init")
            addFunction(initName, irBuiltIns.unitType, visibility = DescriptorVisibilities.PRIVATE)
              .apply {
                val localReceiver = thisReceiverParameter.copyTo(this)
                setDispatchReceiver(localReceiver)
                buildBlockBody {
                  for (statement in statementsChunk) {
                    +statement(localReceiver)
                  }
                }
              }
          }
        constructorStatements += buildList {
          for (initFunction in initFunctionsToCall) {
            add { dispatchReceiver ->
              irInvoke(dispatchReceiver = irGet(dispatchReceiver), callee = initFunction.symbol)
            }
          }
        }
      } else {
        // Small graph, just do it in the constructor
        // Assign those initializers directly to their fields and mark them as final
        for ((field, init) in fieldInitializers) {
          field.initFinal {
            val typeKey = fieldsToTypeKeys.getValue(field)
            init(thisReceiverParameter, typeKey)
          }
        }
        constructorStatements += initStatements
      }

      // Add extra constructor statements
      with(ctor) {
        val originalBody = checkNotNull(body)
        buildBlockBody {
          +originalBody.statements
          for (statement in constructorStatements) {
            +statement(thisReceiverParameter)
          }
        }
      }

      parentTracer.traceNested("Implement overrides") { tracer ->
        node.implementOverrides(baseGenerationContext, tracer)
      }

      if (graphClass.origin != Origins.GeneratedGraphExtension) {
        parentTracer.traceNested("Generate Metro metadata") {
          // Finally, generate metadata
          val graphProto = node.toProto(bindingGraph = bindingGraph)
          val metroMetadata = MetroMetadata(METRO_VERSION, dependency_graph = graphProto)

          writeDiagnostic({
            "graph-metadata-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
          }) {
            metroMetadata.toString()
          }

          // Write the metadata to the metroGraph class, as that's what downstream readers are
          // looking at and is the most complete view
          graphClass.metroMetadata = metroMetadata
          dependencyGraphNodesByClass(node.sourceGraph.classIdOrFail)?.let { it.proto = graphProto }
        }
      }

      if (node.hasExtensions) {
        // Expose getters for provider and instance fields and expose them to metadata
        val providerFieldsSet = providerFields.values.toSet()
        sequence {
            for (entry in providerFields) {
              val binding = bindingGraph.requireBinding(entry.key, IrBindingStack.empty())
              if (
                !binding.isScoped() &&
                  binding !is IrBinding.BoundInstance &&
                  binding !is IrBinding.GraphDependency
              ) {
                // Don't expose redundant accessors for unscoped bindings. BoundInstance bindings
                // still get passed on. GraphDependency bindings (if it reached here) should also
                // pass on
                continue
              }
              yield(entry)
            }
            yieldAll(instanceFields.entries)
          }
          .distinctBy {
            // Only generate once per field. Can happen for cases
            // where we add convenience keys for graph instance supertypes.
            it.value
          }
          .forEach { (key, field) ->
            val isInstanceAccessor = key in instanceFields && field !in providerFieldsSet
            // Deterministic name for provider fields or included
            val getter =
              key.toAccessorFunctionIn(graphClass, wrapInProvider = !isInstanceAccessor).also {
                graphClass.addChild(it)
              }

            getter.apply {
              key.qualifier?.let {
                annotations +=
                  it.ir.transform(DeepCopyIrTreeWithSymbols(SymbolRemapper.EMPTY), null)
                    as IrConstructorCall
              }
              // Add Deprecated(HIDDEN) annotation to hide
              annotations += hiddenDeprecated()
              // Annotate with @MetroAccessor
              annotations +=
                buildAnnotation(symbol, symbols.metroAccessorAnnotationConstructor) { call ->
                  if (isInstanceAccessor) {
                    // Set isInstanceAccessor
                    call.arguments[0] = irBoolean(true)
                  }
                }
              body =
                createIrBuilder(symbol).run {
                  val expression =
                    if (isInstanceAccessor) {
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
            metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(getter)
          }
      }
    }

  // TODO add asProvider support?
  private fun IrClass.addSimpleInstanceField(
    name: String,
    typeKey: IrTypeKey,
    initializerExpression: IrBuilderWithScope.() -> IrExpression,
  ): IrField =
    addField(
        fieldName = name.removePrefix("$$").decapitalizeUS(),
        fieldType = typeKey.type,
        fieldVisibility = DescriptorVisibilities.PRIVATE,
      )
      .initFinal { initializerExpression() }

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
          createIrBuilder(symbol).run {
            if (binding is IrBinding.Multibinding) {
              // TODO if we have multiple accessors pointing at the same type, implement
              //  one and make the rest call that one. Not multibinding specific. Maybe
              //  groupBy { typekey }?
            }
            irExprBodySafe(
              symbol,
              typeAsProviderArgument(
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
    injectors.forEach { (overriddenFunction, contextKey) ->
      val typeKey = contextKey.typeKey
      overriddenFunction.ir.apply {
        finalizeFakeOverride(context.thisReceiver)
        val targetParam = regularParameters[0]
        val binding =
          bindingGraph.requireBinding(contextKey, IrBindingStack.empty())
            as IrBinding.MembersInjected

        // We don't get a MembersInjector instance/provider from the graph. Instead, we call
        // all the target inject functions directly
        body =
          createIrBuilder(symbol).irBlockBody {
            // TODO reuse, consolidate calling code with how we implement this in
            //  constructor inject code gen
            // val injectors =
            // membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)
            // val memberInjectParameters = injectors.flatMap { it.parameters.values.flatten()
            // }

            // Extract the type from MembersInjector<T>
            val wrappedType =
              typeKey.copy(typeKey.type.expectAs<IrSimpleType>().arguments[0].typeOrFail)

            for (type in
              pluginContext
                .referenceClass(binding.targetClassId)!!
                .owner
                .getAllSuperTypes(excludeSelf = false, excludeAny = true)) {
              val clazz = type.rawType()
              val generatedInjector =
                membersInjectorTransformer.getOrGenerateInjector(clazz) ?: continue
              for ((function, unmappedParams) in generatedInjector.declaredInjectFunctions) {
                val parameters =
                  if (typeKey.hasTypeArgs) {
                    val remapper = function.typeRemapperFor(wrappedType.type)
                    function.parameters(remapper)
                  } else {
                    unmappedParams
                  }
                // Record for IC
                trackFunctionCall(this@apply, function)
                +irInvoke(
                  dispatchReceiver = irGetObject(function.parentAsClass.symbol),
                  callee = function.symbol,
                  args =
                    buildList {
                      add(irGet(targetParam))
                      // Always drop the first parameter when calling inject, as the first is the
                      // instance param
                      for (parameter in parameters.regularParameters.drop(1)) {
                        val paramBinding =
                          bindingGraph.requireBinding(
                            parameter.contextualTypeKey,
                            IrBindingStack.empty(),
                          )
                        add(
                          typeAsProviderArgument(
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
    // Note we can't source this from the node.bindsCallables as those are pointed at their original
    // declarations and we need to implement their fake overrides here
    bindsFunctions.forEach { function ->
      function.ir.apply {
        val declarationToFinalize = propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
        }
        body = stubExpressionBody()
      }
    }

    // Implement bodies for contributed graphs
    // Sort by keys when generating so they have deterministic ordering
    // TODO make the value types something more strongly typed
    for ((typeKey, function) in graphExtensions) {
      function.ir.apply {
        val declarationToFinalize =
          function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
        }
        val irFunction = this

        // Check if the return type is a factory interface
        val returnType = irFunction.returnType
        val returnClass = returnType.rawTypeOrNull()

        // Check if this is a GraphExtension.Factory or ContributesGraphExtension.Factory
        val isFactory =
          if (returnClass != null && returnClass.name == Symbols.Names.FactoryClass) {
            val parentClass = returnClass.parent as? IrClass
            parentClass != null &&
              (parentClass.isAnnotatedWithAny(symbols.classIds.graphExtensionAnnotations) ||
                parentClass.isAnnotatedWithAny(
                  symbols.classIds.contributesGraphExtensionAnnotations
                ))
          } else {
            false
          }

        if (isFactory) {
          // For factories, we need to get the actual graph extension type from the factory's SAM
          // return type
          val samMethod = returnClass!!.singleAbstractFunction()
          val graphExtensionType = samMethod.returnType
          val graphExtensionTypeKey = IrTypeKey(graphExtensionType)

          // Generate factory implementation
          body =
            createIrBuilder(symbol).run {
              irExprBodySafe(
                symbol,
                generateGraphExtensionFactory(
                    returnClass,
                    graphExtensionTypeKey,
                    function,
                    parentTracer,
                  )
                  .apply { arguments[0] = irGet(function.ir.dispatchReceiverParameter!!) },
              )
            }
        } else {
          // Direct graph extension accessor. Use regular binding code gen
          val contextKey = IrContextualTypeKey.from(function.ir)
          body =
            createIrBuilder(symbol).run {
              irExprBodySafe(
                symbol,
                generateBindingCode(
                  binding = bindingGraph.requireBinding(typeKey, IrBindingStack.empty()),
                  generationContext = context.withReceiver(irFunction.dispatchReceiverParameter!!),
                  invokeIfProvider = !contextKey.isWrappedInProvider,
                ),
              )
            }
        }
      }
    }
  }

  private fun implementGraphExtensionFactorySAM(
    function: IrSimpleFunction,
    target: IrClass,
    parentGraphArg: IrBuilderWithScope.() -> IrExpression,
  ) {
    val ctor = target.primaryConstructor!!
    function.body =
      createIrBuilder(function.symbol).run {
        irExprBodySafe(
          function.symbol,
          irCallConstructor(ctor.symbol, emptyList()).apply {
            // First arg is always the graph instance
            arguments[0] = parentGraphArg()
            for (i in 0 until function.regularParameters.size) {
              arguments[i + 1] = irGet(function.regularParameters[i])
            }
          },
        )
      }
  }

  private fun IrBuilderWithScope.generateBindingArguments(
    targetParams: Parameters,
    function: IrFunction,
    binding: IrBinding,
    generationContext: GraphGenerationContext,
  ): List<IrExpression?> {
    val params = function.parameters()
    // TODO only value args are supported atm
    var paramsToMap = buildList {
      if (
        binding is IrBinding.Provided &&
          targetParams.dispatchReceiverParameter?.type?.rawTypeOrNull()?.isObject != true
      ) {
        targetParams.dispatchReceiverParameter?.let(::add)
      }
      addAll(targetParams.regularParameters.filterNot { it.isAssisted })
    }

    // Handle case where function has more parameters than the binding
    // This can happen when parameters are inherited from ancestor classes
    if (
      binding is IrBinding.MembersInjected && function.regularParameters.size > paramsToMap.size
    ) {
      // For MembersInjected, we need to look at the target class and its ancestors
      val nameToParam = mutableMapOf<Name, Parameter>()
      val targetClass = pluginContext.referenceClass(binding.targetClassId)?.owner
      targetClass // Look for inject methods in the target class and its ancestors
        ?.getAllSuperTypes(excludeSelf = false, excludeAny = true)
        ?.forEach { type ->
          val clazz = type.rawType()
          membersInjectorTransformer
            .getOrGenerateInjector(clazz)
            ?.declaredInjectFunctions
            ?.forEach { (_, params) ->
              for (param in params.regularParameters) {
                nameToParam.putIfAbsent(param.name, param)
              }
            }
        }
      // Construct the list of parameters in order determined by the function
      paramsToMap =
        function.regularParameters.mapNotNull { functionParam -> nameToParam[functionParam.name] }
      // If we still have a mismatch, log a detailed error
      check(function.regularParameters.size == paramsToMap.size) {
        """
        Inconsistent parameter types for type ${binding.typeKey}!
        Input type keys:
          - ${paramsToMap.map { it.typeKey }.joinToString()}
        Binding parameters (${function.kotlinFqName}):
          - ${function.regularParameters.map { IrContextualTypeKey.from(it).typeKey }.joinToString()}
        """
          .trimIndent()
      }
    }

    if (
      binding is IrBinding.Provided &&
        binding.providerFactory.function.correspondingPropertySymbol == null
    ) {
      check(params.regularParameters.size == paramsToMap.size) {
        """
        Inconsistent parameter types for type ${binding.typeKey}!
        Input type keys:
          - ${paramsToMap.map { it.typeKey }.joinToString()}
        Binding parameters (${function.kotlinFqName}):
          - ${function.regularParameters.map { IrContextualTypeKey.from(it).typeKey }.joinToString()}
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

          if (paramBinding is IrBinding.Absent) {
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
        param.contextualTypeKey,
        providerInstance,
        isAssisted = param.isAssisted,
        isGraphInstance = param.isGraphInstance,
      )
    }
  }

  private fun generateMapKeyLiteral(binding: IrBinding): IrExpression {
    val mapKey =
      when (binding) {
        is IrBinding.Alias -> binding.annotations.mapKeys.first().ir
        is IrBinding.Provided -> binding.annotations.mapKeys.first().ir
        is IrBinding.ConstructorInjected -> binding.annotations.mapKeys.first().ir
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
    binding: IrBinding,
    generationContext: GraphGenerationContext,
    contextualTypeKey: IrContextualTypeKey = binding.contextualTypeKey,
    invokeIfProvider: Boolean = false,
    fieldInitKey: IrTypeKey? = null,
  ): IrExpression {
    if (binding is IrBinding.Absent) {
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
        val providerInstance =
          irGetField(irGet(generationContext.thisReceiver), it).let {
            with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
          }
        return if (invokeIfProvider) {
          irInvoke(providerInstance, callee = symbols.providerInvoke)
        } else {
          providerInstance
        }
      }
    }

    return when (binding) {
      is IrBinding.ConstructorInjected -> {
        // Example_Factory.create(...)
        val factory = binding.classFactory

        with(factory) {
          invokeCreateExpression { createFunction ->
            val remapper = createFunction.typeRemapperFor(binding.typeKey.type)
            generateBindingArguments(
              createFunction.parameters(remapper = remapper),
              createFunction.deepCopyWithSymbols(initialParent = createFunction.parent).also {
                it.parent = createFunction.parent
                it.remapTypes(remapper)
              },
              binding,
              generationContext,
            )
          }
        }
      }

      is IrBinding.ObjectClass -> {
        instanceFactory(binding.typeKey.type, irGetObject(binding.type.symbol))
      }

      is IrBinding.Alias -> {
        // For binds functions, just use the backing type
        val aliasedBinding = binding.aliasedBinding(bindingGraph, IrBindingStack.empty())
        check(aliasedBinding != binding) { "Aliased binding aliases itself" }
        return generateBindingCode(aliasedBinding, generationContext)
      }

      is IrBinding.Provided -> {
        val factoryClass =
          bindingContainerTransformer.getOrLookupProviderFactory(binding)?.clazz
            ?: error(
              "No factory found for Provided binding ${binding.typeKey}. This is likely a bug in the Metro compiler, please report it to the issue tracker."
            )

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

      is IrBinding.Assisted -> {
        // Example9_Factory_Impl.create(example9Provider);
        val implClass =
          assistedFactoryTransformer.getOrGenerateImplClass(binding.type) ?: return stubExpression()

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

      is IrBinding.Multibinding -> {
        generateMultibindingExpression(binding, contextualTypeKey, generationContext, fieldInitKey)
      }

      is IrBinding.MembersInjected -> {
        val injectedClass = referenceClass(binding.targetClassId)!!.owner
        val injectedType = injectedClass.defaultType
        val injectorClass = membersInjectorTransformer.getOrGenerateInjector(injectedClass)?.ir

        if (injectorClass == null) {
          // Return a noop
          val noopInjector =
            irInvoke(
              dispatchReceiver = irGetObject(symbols.metroMembersInjectors),
              callee = symbols.metroMembersInjectorsNoOp,
              typeArgs = listOf(injectedType),
            )
          instanceFactory(noopInjector.type, noopInjector).let {
            with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
          }
        } else {
          val injectorCreatorClass =
            if (injectorClass.isObject) injectorClass else injectorClass.companionObject()!!
          val createFunction =
            injectorCreatorClass.requireSimpleFunction(Symbols.StringNames.CREATE)
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
                  if (injectorCreatorClass.isObject) {
                    irGetObject(injectorCreatorClass.symbol)
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

      is IrBinding.Absent -> {
        // Should never happen, this should be checked before function/constructor injections.
        error("Unable to generate code for unexpected Absent binding: $binding")
      }

      is IrBinding.BoundInstance -> {
        // Should never happen, this should get handled in the provider/instance fields logic above.
        error("Unable to generate code for unexpected BoundInstance binding: $binding")
      }

      is IrBinding.GraphExtension -> {
        // Generate graph extension instance
        val extensionImpl =
          contributedGraphGenerator.getOrBuildGraphExtensionImpl(
            binding.typeKey,
            node.sourceGraph,
            // The reportableDeclaration should be the accessor function
            metroFunctionOf(binding.reportableDeclaration as IrSimpleFunction),
            parentTracer,
          )

        val ctor = extensionImpl.primaryConstructor!!
        val instanceExpression =
          irCallConstructor(ctor.symbol, node.sourceGraph.typeParameters.map { it.defaultType })
            .apply {
              val ctor = extensionImpl.primaryConstructor!!
              val remapper = ctor.typeRemapperFor(binding.typeKey.type)
              val parameters = ctor.parameters(remapper = remapper)

              // If this function has parameters, they're factory instance params and need to be passed on
              val functionParams = binding.accessor.regularParameters
              
              // First param is always the parent graph
              arguments[0] = irGet(generationContext.thisReceiver)
              for (i in 0 until functionParams.size) {
                arguments[i + 1] = irGet(functionParams[i])
              }
            }
        if (invokeIfProvider) {
          // Already not a provider
          instanceExpression
        } else {
          instanceFactory(binding.typeKey.type, instanceExpression)
        }
      }

      is IrBinding.GraphDependency -> {
        val ownerKey = binding.ownerKey
        val graphInstanceField =
          instanceFields[ownerKey]
            ?: run {
              error(
                "No matching included type instance found for type $ownerKey while processing ${node.typeKey}. Available instance fields ${instanceFields.keys}"
              )
            }

        val getterContextKey = IrContextualTypeKey.from(binding.getter)

        val invokeGetter =
          irInvoke(
            dispatchReceiver =
              irGetField(irGet(generationContext.thisReceiver), graphInstanceField),
            callee = binding.getter.symbol,
            typeHint = binding.typeKey.type,
          )

        if (getterContextKey.isLazyWrappedInProvider) {
          // TODO FIR this
          diagnosticReporter
            .at(binding.getter)
            .report(MetroIrErrors.METRO_ERROR, "Provider<Lazy<T>> accessors are not supported.")
          exitProcessing()
        } else if (getterContextKey.isWrappedInProvider) {
          // It's already a provider
          invokeGetter
        } else {
          val lambda =
            irLambda(
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
    binding: IrBinding.Multibinding,
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
    binding: IrBinding.Multibinding,
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
            .expectAs<IrBinding.BindingWithAnnotations>()
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
    binding: IrBinding.Multibinding,
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
              parent = parent,
              receiverParameter = irBuiltIns.mutableSetClass.typeWith(elementType),
              valueParameters = emptyList(),
              returnType = irBuiltIns.unitType,
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
    collectionProviders: List<IrBinding>,
    individualProviders: List<IrBinding>,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    // SetFactory.<String>builder(1, 1)
    //   .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
    //   .addCollectionProvider(provideString2Provider)
    //   .build()

    // Used to unpack the right provider type
    val valueProviderSymbols = symbols.providerSymbolsFor(elementType)

    // SetFactory.<String>builder(1, 1)
    val builder: IrExpression =
      irInvoke(
        callee = valueProviderSymbols.setFactoryBuilderFunction,
        typeHint = valueProviderSymbols.setFactoryBuilder.typeWith(elementType),
        typeArgs = listOf(elementType),
        args = listOf(irInt(individualProviders.size), irInt(collectionProviders.size)),
      )

    val withProviders =
      individualProviders.fold(builder) { receiver, provider ->
        irInvoke(
          dispatchReceiver = receiver,
          callee = valueProviderSymbols.setFactoryBuilderAddProviderFunction,
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
          callee = valueProviderSymbols.setFactoryBuilderAddCollectionProviderFunction,
          typeHint = builder.type,
          args =
            listOf(generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey)),
        )
      }

    // .build()
    val instance =
      irInvoke(
        dispatchReceiver = withCollectionProviders,
        callee = valueProviderSymbols.setFactoryBuilderBuildFunction,
        typeHint = irBuiltIns.setClass.typeWith(elementType).wrapInProvider(symbols.metroProvider),
      )
    return with(valueProviderSymbols) {
      transformToMetroProvider(instance, irBuiltIns.setClass.typeWith(elementType))
    }
  }

  private fun IrBuilderWithScope.generateMapMultibindingExpression(
    binding: IrBinding.Multibinding,
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
    val rawValueTypeMetadata = rawValueType.typeOrFail.asContextualTypeKey(null, hasDefault = false)

    // TODO what about Map<String, Provider<Lazy<String>>>?
    //  isDeferrable() but we need to be able to convert back to the middle type
    val useProviderFactory: Boolean = valueWrappedType is WrappedType.Provider

    // Used to unpack the right provider type
    val originalType = contextualTypeKey.toIrType()
    val originalValueType = valueWrappedType.toIrType()
    val originalValueContextKey = originalValueType.asContextualTypeKey(null, hasDefault = false)
    val valueProviderSymbols = symbols.providerSymbolsFor(originalValueType)

    val valueType: IrType = rawValueTypeMetadata.typeKey.type

    val size = binding.sourceBindings.size
    val mapProviderType =
      irBuiltIns.mapClass
        .typeWith(
          keyType,
          if (useProviderFactory) {
            rawValueType.wrapInProvider(symbols.metroProvider)
          } else {
            rawValueType
          },
        )
        .wrapInProvider(symbols.metroProvider)

    if (size == 0) {
      // If it's empty then short-circuit here
      return if (useProviderFactory) {
        // MapProviderFactory.empty()
        val emptyCallee = valueProviderSymbols.mapProviderFactoryEmptyFunction
        if (emptyCallee != null) {
          irInvoke(callee = emptyCallee, typeHint = mapProviderType)
        } else {
          // Call builder().build()
          // build()
          irInvoke(
            callee = valueProviderSymbols.mapProviderFactoryBuilderBuildFunction,
            typeHint = mapProviderType,
            // builder()
            dispatchReceiver =
              irInvoke(
                callee = valueProviderSymbols.mapProviderFactoryBuilderFunction,
                typeHint = mapProviderType,
                args = listOf(irInt(0)),
              ),
          )
        }
      } else {
        // MapFactory.empty()
        irInvoke(callee = valueProviderSymbols.mapFactoryEmptyFunction, typeHint = mapProviderType)
      }
    }

    val builderFunction =
      if (useProviderFactory) {
        valueProviderSymbols.mapProviderFactoryBuilderFunction
      } else {
        valueProviderSymbols.mapFactoryBuilderFunction
      }
    val builderType =
      if (useProviderFactory) {
        valueProviderSymbols.mapProviderFactoryBuilder
      } else {
        valueProviderSymbols.mapFactoryBuilder
      }

    // MapFactory.<Integer, Integer>builder(2)
    // MapProviderFactory.<Integer, Integer>builder(2)
    val builder: IrExpression =
      irInvoke(
        callee = builderFunction,
        typeArgs = listOf(keyType, valueType),
        typeHint = builderType.typeWith(keyType, valueType),
        args = listOf(irInt(size)),
      )

    val putFunction =
      if (useProviderFactory) {
        valueProviderSymbols.mapProviderFactoryBuilderPutFunction
      } else {
        valueProviderSymbols.mapFactoryBuilderPutFunction
      }
    val putAllFunction =
      if (useProviderFactory) {
        valueProviderSymbols.mapProviderFactoryBuilderPutAllFunction
      } else {
        valueProviderSymbols.mapFactoryBuilderPutAllFunction
      }

    val withProviders =
      binding.sourceBindings
        .map { bindingGraph.requireBinding(it, IrBindingStack.empty()) }
        .fold(builder) { receiver, sourceBinding ->
          val providerTypeMetadata = sourceBinding.contextualTypeKey

          // TODO FIR this should be an error actually
          val isMap = providerTypeMetadata.typeKey.type.rawType().symbol == irBuiltIns.mapClass

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
                generateBindingCode(sourceBinding, generationContext, fieldInitKey = fieldInitKey)
                  .let {
                    with(valueProviderSymbols) {
                      transformMetroProvider(it, originalValueContextKey)
                    }
                  },
              ),
          )
        }

    // .build()
    val buildFunction =
      if (useProviderFactory) {
        valueProviderSymbols.mapProviderFactoryBuilderBuildFunction
      } else {
        valueProviderSymbols.mapFactoryBuilderBuildFunction
      }

    val instance =
      irInvoke(dispatchReceiver = withProviders, callee = buildFunction, typeHint = mapProviderType)
    return with(valueProviderSymbols) { transformToMetroProvider(instance, originalType) }
  }

  private fun IrBuilderWithScope.generateMultibindingArgument(
    provider: IrBinding,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val bindingCode = generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey)
    return typeAsProviderArgument(
      contextKey = IrContextualTypeKey.create(provider.typeKey),
      bindingCode = bindingCode,
      isAssisted = false,
      isGraphInstance = false,
    )
  }

  private fun IrBuilderWithScope.generateGraphExtensionFactory(
    factoryInterface: IrClass,
    graphExtensionTypeKey: IrTypeKey,
    function: MetroSimpleFunction,
    parentTracer: Tracer,
  ): IrConstructorCall {
    // Generate the contributed graph for the extension
    val contributedGraph =
      contributedGraphGenerator.getOrBuildGraphExtensionImpl(
        graphExtensionTypeKey,
        node.sourceGraph,
        function,
        parentTracer,
      )

    // Create the factory implementation as a nested class
    // TODO make this a local/anonymous class instead?
    val factoryImpl =
      pluginContext.irFactory
        .buildClass {
          name =
            nestedClassNameAllocator
              .newName("${factoryInterface.name}${Symbols.StringNames.METRO_IMPL}")
              .asName()
          kind = ClassKind.CLASS
          visibility = DescriptorVisibilities.PRIVATE
          origin = Origins.Default
        }
        .apply {
          this.superTypes = listOf(factoryInterface.defaultType)
          this.typeParameters = copyTypeParametersFrom(factoryInterface)
          this.createThisReceiverParameter()
          graphClass.addChild(this)
          this.addFakeOverrides(metroContext.irTypeSystemContext)
        }

    val constructor =
      factoryImpl
        .addConstructor {
          visibility = DescriptorVisibilities.PUBLIC
          isPrimary = true
          this.returnType = factoryImpl.defaultType
        }
        .apply {
          addValueParameter("parentInstance", graphClass.defaultType)
          body = generateDefaultConstructorBody()
        }

    val paramsToFields = assignConstructorParamsToFields(constructor, factoryImpl)

    // Implement the SAM method
    val samFunction = factoryImpl.singleAbstractFunction()
    samFunction.finalizeFakeOverride(factoryImpl.thisReceiverOrFail)
    implementGraphExtensionFactorySAM(samFunction, contributedGraph) {
      irGetField(irGet(samFunction.dispatchReceiverParameter!!), paramsToFields.values.first())
    }

    // Return an instance of the factory implementation
    return irCallConstructor(constructor.symbol, function.ir.typeParameters.map { it.defaultType })
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
