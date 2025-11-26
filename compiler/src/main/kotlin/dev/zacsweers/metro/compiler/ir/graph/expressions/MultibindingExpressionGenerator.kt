// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.expressions

import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.shouldUnwrapMapKeyValues
import dev.zacsweers.metro.compiler.ir.stripLazy
import dev.zacsweers.metro.compiler.ir.toIrType
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.FrameworkSymbols
import dev.zacsweers.metro.compiler.tracing.Tracer
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.nonDispatchParameters

private typealias MultibindingExpression =
  IrBuilderWithScope.(MultibindingExpressionGenerator) -> IrExpression

internal class MultibindingExpressionGenerator(
  private val parentGenerator: BindingExpressionGenerator<IrBinding>,
  private val getterPropertyFor:
    (
      IrBinding, IrContextualTypeKey, IrBuilderWithScope.(MultibindingExpressionGenerator) -> IrBody,
    ) -> IrProperty,
) : BindingExpressionGenerator<IrBinding.Multibinding>(parentGenerator) {
  override val thisReceiver: IrValueParameter
    get() = parentGenerator.thisReceiver

  override val bindingGraph: IrBindingGraph
    get() = parentGenerator.bindingGraph

  override val parentTracer: Tracer
    get() = parentGenerator.parentTracer

  context(scope: IrBuilderWithScope)
  override fun generateBindingCode(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    accessType: AccessType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    // need to change this to a Metro Provider for our generation
    val transformedContextKey =
      contextualTypeKey.letIf(contextualTypeKey.requiresProviderInstance) {
        contextualTypeKey.stripLazy().wrapInProvider()
      }
    return if (binding.isSet) {
      generateSetMultibindingExpression(binding, accessType, transformedContextKey, fieldInitKey)
    } else {
      // It's a map
      generateMapMultibindingExpression(binding, transformedContextKey, accessType, fieldInitKey)
    }
  }

  context(scope: IrBuilderWithScope)
  private fun generateSetMultibindingExpression(
    binding: IrBinding.Multibinding,
    accessType: AccessType,
    contextualTypeKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      val generateCode: MultibindingExpression = { expressionGenerator ->
        expressionGenerator.buildSetMultibindingExpression(
          binding,
          accessType,
          contextualTypeKey,
          fieldInitKey,
        )
      }

      if (binding.isEmpty()) {
        // Short-circuit and generate the empty call directly
        return generateCode(this@MultibindingExpressionGenerator)
      }

      // Use lazy property to cache the multibinding
      val property =
        getterPropertyFor(binding, contextualTypeKey) { expressionGenerator ->
          irExprBodySafe(generateCode(expressionGenerator))
        }

      // Return the property access, which will be the provider
      return irGetProperty(irGet(thisReceiver), property)
    }

  context(scope: IrBuilderWithScope)
  private fun buildSetMultibindingExpression(
    binding: IrBinding.Multibinding,
    accessType: AccessType,
    contextualTypeKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val elementType = (binding.typeKey.type as IrSimpleType).arguments.single().typeOrFail
    val (collectionProviders, individualProviders) =
      binding.sourceBindings
        .map { bindingGraph.requireBinding(it).expectAs<IrBinding.BindingWithAnnotations>() }
        .partition { it.annotations.isElementsIntoSet }

    val actualAccessType: AccessType
    // If we have any @ElementsIntoSet, we need to use SetFactory
    val instance =
      if (collectionProviders.isNotEmpty() || accessType == AccessType.PROVIDER) {
        actualAccessType = AccessType.PROVIDER
        generateSetFactoryExpression(
          elementType,
          collectionProviders,
          individualProviders,
          fieldInitKey,
        )
      } else {
        actualAccessType = AccessType.INSTANCE
        generateSetBuilderExpression(binding, elementType, fieldInitKey)
      }
    return instance.toTargetType(actual = actualAccessType, contextualTypeKey = contextualTypeKey)
  }

  context(scope: IrBuilderWithScope)
  private fun generateSetBuilderExpression(
    binding: IrBinding.Multibinding,
    elementType: IrType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      val callee: IrSimpleFunctionSymbol
      val args: List<IrExpression>
      when (val size = binding.sourceBindings.size) {
        0 -> {
          // emptySet()
          callee = metroSymbols.emptySet
          args = emptyList()
        }

        1 -> {
          // setOf(<one>)
          callee = metroSymbols.setOfSingleton
          val provider = binding.sourceBindings.first().let { bindingGraph.requireBinding(it) }
          args =
            listOf(
              generateMultibindingArgument(
                provider,
                provider.contextualTypeKey,
                fieldInitKey,
                accessType = AccessType.INSTANCE,
              )
            )
        }

        else -> {
          // buildSet(<size>) { ... }
          callee = metroSymbols.buildSetWithCapacity
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
                  .map { bindingGraph.requireBinding(it) }
                  .forEach { binding ->
                    +irInvoke(
                      dispatchReceiver = irGet(functionReceiver),
                      callee = metroSymbols.mutableSetAdd.symbol,
                      args =
                        listOf(
                          generateMultibindingArgument(
                            binding,
                            binding.contextualTypeKey,
                            fieldInitKey,
                            accessType = AccessType.PROVIDER,
                          )
                        ),
                    )
                  }
              }
            )
          }
        }
      }

      return irCall(
          callee = callee,
          type = binding.typeKey.type,
          typeArguments = listOf(elementType),
        )
        .apply {
          for ((i, arg) in args.withIndex()) {
            arguments[i] = arg
          }
        }
    }

  private fun generateMapKeyLiteral(binding: IrBinding): IrExpression {
    val mapKey =
      when (binding) {
        is IrBinding.BindingWithAnnotations -> binding.annotations.mapKeys.first().ir
        else -> reportCompilerBug("Unsupported multibinding source: $binding")
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

  context(scope: IrBuilderWithScope)
  private fun generateMapBuilderExpression(
    binding: IrBinding.Multibinding,
    size: Int,
    keyType: IrType,
    valueType: IrType,
    originalValueContextKey: IrContextualTypeKey,
    valueAccessType: AccessType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      // buildMap(size) { put(key, value) ... }
      return irCall(
          callee = metroSymbols.buildMapWithCapacity,
          type = irBuiltIns.mapClass.typeWith(keyType, valueType),
          typeArguments = listOf(keyType, valueType),
        )
        .apply {
          arguments[0] = irInt(size)
          arguments[1] =
            irLambda(
              parent = parent,
              receiverParameter = irBuiltIns.mutableMapClass.typeWith(keyType, valueType),
              valueParameters = emptyList(),
              returnType = irBuiltIns.unitType,
              suspend = false,
            ) { function ->
              // This is the mutable map receiver
              val functionReceiver = function.extensionReceiverParameterCompat!!
              binding.sourceBindings
                .map { bindingGraph.requireBinding(it) }
                .forEach { binding ->
                  +irInvoke(
                    dispatchReceiver = irGet(functionReceiver),
                    callee = metroSymbols.mutableMapPut.symbol,
                    args =
                      listOf(
                        generateMapKeyLiteral(binding),
                        generateMultibindingArgument(
                          binding,
                          originalValueContextKey,
                          fieldInitKey,
                          accessType = valueAccessType,
                        ),
                      ),
                  )
                }
            }
        }
    }

  // TODO
  //  test failures
  //  bindingpropertycollector - if a provider is used in multiple multibindings, field instead of
  //  lazy getter?
  //  or - geneate property, for instance. If a provider is requested instead, generate that then
  //  update the instance access to call that + get()
  context(scope: IrBuilderWithScope)
  private fun generateSetFactoryExpression(
    elementType: IrType,
    collectionProviders: List<IrBinding>,
    individualProviders: List<IrBinding>,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      /*
        SetFactory.<String>builder(1, 1)
          .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
          .addCollectionProvider(provideString2Provider)
          .build()
      */

      // Used to unpack the right provider type
      val valueProviderSymbols = metroSymbols.providerSymbolsFor(elementType)
      val providerClass =
        valueProviderSymbols.setFactoryBuilderAddProviderFunction.owner.regularParameters[0]
          .type
          .classOrFail
          .owner

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
              listOf(
                parentGenerator.generateBindingCode(
                  provider,
                  provider.contextualTypeKey.wrapInProvider(providerClass),
                  accessType = AccessType.PROVIDER,
                  fieldInitKey = fieldInitKey,
                )
              ),
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
              listOf(
                parentGenerator.generateBindingCode(
                  provider,
                  provider.contextualTypeKey.wrapInProvider(providerClass),
                  accessType = AccessType.PROVIDER,
                  fieldInitKey = fieldInitKey,
                )
              ),
          )
        }

      // .build()
      val instance =
        irInvoke(
          dispatchReceiver = withCollectionProviders,
          callee = valueProviderSymbols.setFactoryBuilderBuildFunction,
          typeHint =
            irBuiltIns.setClass.typeWith(elementType).wrapInProvider(metroSymbols.metroProvider),
        )
      return with(metroSymbols.providerTypeConverter) {
        instance.convertTo(
          IrContextualTypeKey(IrTypeKey(irBuiltIns.setClass.typeWith(elementType))).wrapInProvider()
        )
      }
    }

  context(scope: IrBuilderWithScope)
  private fun generateMapMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    accessType: AccessType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      val generateCode: MultibindingExpression = { expressionGenerator ->
        expressionGenerator.generateMapMultibindingExpressionImpl(
          binding,
          contextualTypeKey,
          accessType,
          fieldInitKey,
        )
      }

      if (binding.isEmpty()) {
        // Short-circuit and generate the empty call directly
        return generateCode(this@MultibindingExpressionGenerator)
      }

      // Use lazy property to cache the multibinding and handle different access patterns
      val property =
        getterPropertyFor(binding, contextualTypeKey) { expressionGenerator ->
          irExprBodySafe(generateCode(expressionGenerator))
        }

      // Return the property access, which will be the provider
      return irGetProperty(irGet(thisReceiver), property)
    }

  context(scope: IrBuilderWithScope)
  private fun generateMapMultibindingExpressionImpl(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    accessType: AccessType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      /*
        MapFactory.<Integer, Integer>builder(2)
          .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
          .put(2, provideMapInt2Provider)
          .build()
        MapProviderFactory.<Integer, Integer>builder(2)
          .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
          .put(2, provideMapInt2Provider)
          .build()
      */

      val valueWrappedType = contextualTypeKey.wrappedType.findMapValueType()!!

      val mapTypeArgs = (contextualTypeKey.typeKey.type as IrSimpleType).arguments
      check(mapTypeArgs.size == 2) { "Unexpected map type args: ${mapTypeArgs.joinToString()}" }
      val keyType: IrType = mapTypeArgs[0].typeOrFail
      val rawValueType = mapTypeArgs[1].typeOrFail
      val rawValueTypeMetadata =
        rawValueType.typeOrFail.asContextualTypeKey(
          null,
          hasDefault = false,
          patchMutableCollections = false,
          declaration = binding.declaration,
        )

      // TODO what about Map<String, Provider<Lazy<String>>>?
      //  isDeferrable() but we need to be able to convert back to the middle type
      val valueIsWrappedInProvider: Boolean = valueWrappedType is WrappedType.Provider

      // Used to unpack the right provider type
      val originalValueType = valueWrappedType.toIrType()
      val originalValueContextKey =
        originalValueType.asContextualTypeKey(
          null,
          hasDefault = false,
          patchMutableCollections = false,
          declaration = binding.declaration,
        )
      val valueProviderSymbols = metroSymbols.providerSymbolsFor(originalValueType)

      val valueType: IrType = rawValueTypeMetadata.typeKey.type

      val size = binding.sourceBindings.size
      val mapProviderType =
        irBuiltIns.mapClass
          .typeWith(
            keyType,
            if (valueIsWrappedInProvider) {
              rawValueType.wrapInProvider(originalValueType.rawType().symbol)
            } else {
              rawValueType
            },
          )
          .let {
            val providerType =
              if (contextualTypeKey.isWrappedInProvider) {
                contextualTypeKey.toIrType().classOrFail
              } else {
                metroSymbols.metroProvider
              }
            it.wrapInProvider(providerType)
          }

      /*
      There are different code paths this may travel depending on
      - number of elements
      - access type
      - value type

      ┌──────────────────┐          ┌──────────────────┐
      │      Empty?      ├────Yes───►    AccessType?   │
      └────────┬─────────┘          └────────┬─────────┘
               │                             │
               No                      ┌─────┴─────┐
               │               ┌─INST──┤AccessType?├───PROV─┐
               │               │       └───────────┘        │
               │               │                      ┌─────┴───┐
               │               │                   Yes│         │No
               │               ▼                      ▼         ▼
               │        ┌────────────┐  ┌────────────────────┐  │
               │        │ emptyMap() │  │ MapProviderFactory │  │
               │        └────────────┘  └────────────────────┘  │
               │                                                │
               │                                        ┌───────┘
               │                                        ▼
               │                                 ┌────────────┐
      ┌────────┴──────┐                          │ MapFactory │
      │   Non-Empty   ├─┐                        └────────────┘
      └───────────────┘ │
                        │       ┌──────────────────┐
                        └──────►│    AccessType    │
                                └───────┬──────────┘
                                        │
                                ┌───────┴──────┐
                                │              │
                                ▼              ▼
                          ┌──────────┐    ┌──────────┐
                          │ INSTANCE │    │ PROVIDER │
                          └─────┬────┘    └────┬─────┘
                                │              │
                                ▼              ▼
                        ┌────────────┐   ┌─────────────┐
                        │ buildMap() │   │ Map*Factory │
                        └────────────┘   └─────────────┘
      */

      val instance =
        if (size == 0) {
          return generateEmptyMapExpression(
            keyType,
            rawValueType,
            mapProviderType,
            valueIsWrappedInProvider,
            valueProviderSymbols,
            accessType,
          )
        } else if (accessType == AccessType.INSTANCE) {
          // Multiple elements but only needs a Map<Key, Value> type
          // Even if the value type is Provider<Value>, we'll denote it with `valueAccessType`
          return generateMapBuilderExpression(
            binding = binding,
            size = size,
            keyType = keyType,
            valueType = valueWrappedType.toIrType(),
            originalValueContextKey = originalValueContextKey,
            valueAccessType =
              if (valueIsWrappedInProvider) AccessType.PROVIDER else AccessType.INSTANCE,
            fieldInitKey = fieldInitKey,
          )
        } else {
          // Multiple elements and it's a Provider type
          val builderFunction =
            if (valueIsWrappedInProvider) {
              valueProviderSymbols.mapProviderFactoryBuilderFunction
            } else {
              valueProviderSymbols.mapFactoryBuilderFunction
            }
          val builderType =
            if (valueIsWrappedInProvider) {
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
            if (valueIsWrappedInProvider) {
              valueProviderSymbols.mapProviderFactoryBuilderPutFunction
            } else {
              valueProviderSymbols.mapFactoryBuilderPutFunction
            }
          val putAllFunction =
            if (valueIsWrappedInProvider) {
              valueProviderSymbols.mapProviderFactoryBuilderPutAllFunction
            } else {
              valueProviderSymbols.mapFactoryBuilderPutAllFunction
            }

          val withProviders =
            binding.sourceBindings
              .map { bindingGraph.requireBinding(it) }
              .fold(builder) { receiver, sourceBinding ->
                val providerTypeMetadata = sourceBinding.contextualTypeKey

                val isMap =
                  providerTypeMetadata.typeKey.type.rawType().symbol == irBuiltIns.mapClass

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

                // Ensure we match the expected parameter type of the put() function we're calling
                val providerType = putter.owner.nonDispatchParameters[1].type.rawType()
                irInvoke(
                  dispatchReceiver = receiver,
                  callee = putter,
                  typeHint = builder.type,
                  args =
                    listOf(
                      generateMapKeyLiteral(sourceBinding),
                      generateMultibindingArgument(
                        sourceBinding,
                        originalValueContextKey.wrapInProvider(providerType),
                        fieldInitKey,
                        accessType = AccessType.PROVIDER,
                      ),
                    ),
                )
              }

          // .build()
          val buildFunction =
            if (valueIsWrappedInProvider) {
              valueProviderSymbols.mapProviderFactoryBuilderBuildFunction
            } else {
              valueProviderSymbols.mapFactoryBuilderBuildFunction
            }

          irInvoke(
            dispatchReceiver = withProviders,
            callee = buildFunction,
            // Wrap in the appropriate Provider type for the symbols we're using
            typeHint =
              valueProviderSymbols.canonicalProviderType.typeWithArguments(
                mapProviderType.requireSimpleType().arguments
              ),
          )
        }

      // Always a provider instance in this branch, no need to transform access type
      val providerTypeConverter = metroSymbols.providerTypeConverter
      val providerInstance = with(providerTypeConverter) { instance.convertTo(contextualTypeKey) }

      return providerInstance
    }

  context(scope: IrBuilderWithScope)
  private fun generateEmptyMapExpression(
    keyType: IrType,
    rawValueType: IrType,
    mapProviderType: IrType,
    valueIsWrappedInProvider: Boolean,
    valueFrameworkSymbols: FrameworkSymbols,
    accessType: AccessType,
  ): IrExpression =
    with(scope) {
      if (accessType == AccessType.INSTANCE) {
        // Type: Map<Key, Value>
        // Returns: emptyMap()
        irInvoke(
          callee = metroSymbols.emptyMap,
          typeHint = irBuiltIns.mapClass.typeWith(keyType, rawValueType),
          typeArgs = listOf(keyType, rawValueType),
        )
      } else if (valueIsWrappedInProvider) {
        // Type: Map<Key, Provider<Value>>

        // Returns:
        //   - MapProviderFactory.empty() (if the API exists)
        //   - MapProviderFactory.builder().build() (if no empty() API exists)
        val emptyCallee = valueFrameworkSymbols.mapProviderFactoryEmptyFunction
        if (emptyCallee != null) {
          irInvoke(
            callee = emptyCallee,
            typeHint = mapProviderType,
            typeArgs = listOf(keyType, rawValueType),
          )
        } else {
          // Call builder().build()
          irInvoke(
            // build()
            callee = valueFrameworkSymbols.mapProviderFactoryBuilderBuildFunction,
            typeHint = mapProviderType,
            dispatchReceiver =
              irInvoke(
                // builder()
                callee = valueFrameworkSymbols.mapProviderFactoryBuilderFunction,
                typeHint = mapProviderType,
                args = listOf(irInt(0)),
              ),
          )
        }
      } else {
        // Type: Provider<Map<Key, Value>>
        // Returns: MapFactory.empty()
        irInvoke(
          callee = valueFrameworkSymbols.mapFactoryEmptyFunction,
          typeHint = mapProviderType,
          typeArgs = listOf(keyType, rawValueType),
        )
      }
    }

  context(scope: IrBuilderWithScope)
  private fun generateMultibindingArgument(
    provider: IrBinding,
    contextKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
    accessType: AccessType,
  ): IrExpression =
    with(scope) {
      val bindingCode =
        parentGenerator.generateBindingCode(
          provider,
          contextKey,
          accessType = accessType,
          fieldInitKey = fieldInitKey,
        )

      return typeAsProviderArgument(
        contextKey = contextKey,
        bindingCode = bindingCode,
        isAssisted = false,
        isGraphInstance = false,
      )
    }
}
