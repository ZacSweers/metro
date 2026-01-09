// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.expressions

import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.createAndAddTemporaryVariable
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.irTemporaryVariable
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.shouldUnwrapMapKeyValues
import dev.zacsweers.metro.compiler.ir.stripIfLazy
import dev.zacsweers.metro.compiler.ir.toIrType
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.FrameworkSymbols
import dev.zacsweers.metro.compiler.tracing.Tracer
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallOp
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.util.OperatorNameConventions

internal class MultibindingExpressionGenerator(
  private val parentGenerator: BindingExpressionGenerator<IrBinding>
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
        contextualTypeKey.stripIfLazy().wrapInProvider()
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
  ): IrExpression {
    val elementType = (binding.typeKey.type as IrSimpleType).arguments.single().typeOrFail
    val (collectionProviders, individualProviders) =
      binding.sourceBindings
        .map { bindingGraph.requireBinding(it) }
        .partition { it.typeKey.multibindingKeyData?.isElementsIntoSet ?: false }

    val actualAccessType: AccessType
    // If we have any @ElementsIntoSet, we need to use SetFactory
    val instance =
      if (accessType == AccessType.PROVIDER) {
        actualAccessType = AccessType.PROVIDER
        generateSetFactoryExpression(
          elementType,
          collectionProviders,
          individualProviders,
          fieldInitKey,
        )
      } else {
        actualAccessType = AccessType.INSTANCE
        generateSetBuilderExpression(
          binding,
          elementType,
          collectionProviders,
          individualProviders,
          fieldInitKey,
        )
      }
    return instance.toTargetType(actual = actualAccessType, contextualTypeKey = contextualTypeKey)
  }

  context(scope: IrBuilderWithScope)
  private fun generateSetBuilderExpression(
    binding: IrBinding.Multibinding,
    elementType: IrType,
    collectionProviders: List<IrBinding>,
    individualProviders: List<IrBinding>,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      when (val individualProvidersSize = individualProviders.size) {
        0 if (collectionProviders.isEmpty()) -> {
          // emptySet()
          val callee = metroSymbols.emptySet
          irInvoke(callee = callee, typeHint = binding.typeKey.type, typeArgs = listOf(elementType))
        }

        1 if (collectionProviders.isEmpty()) -> {
          // setOf(<one>)
          val callee = metroSymbols.setOfSingleton
          val provider = binding.sourceBindings.first().let { bindingGraph.requireBinding(it) }
          val args =
            listOf(
              generateMultibindingArgument(
                provider,
                provider.contextualTypeKey,
                fieldInitKey,
                accessType = AccessType.INSTANCE,
              )
            )
          irInvoke(
            callee = callee,
            typeHint = binding.typeKey.type,
            typeArgs = listOf(elementType),
            args = args,
          )
        }

        else -> {
          // buildSet(<size>) { ... }
          irBlock {
            val callee = metroSymbols.buildSetWithCapacity
            val collectionProviderInstanceVars = mutableListOf<IrVariable>()
            if (collectionProviders.isNotEmpty()) {
              for (provider in collectionProviders) {
                +irTemporaryVariable(
                    generateMultibindingArgument(
                      provider,
                      provider.contextualTypeKey,
                      fieldInitKey,
                      accessType = AccessType.INSTANCE,
                    )
                  )
                  .also(collectionProviderInstanceVars::add)
              }
            }
            var sizeVar: IrExpression? = null
            if (individualProvidersSize != 0) {
              sizeVar = irInt(individualProvidersSize)
            }
            if (collectionProviderInstanceVars.isNotEmpty()) {
              for ((i, collectionProviderVar) in collectionProviderInstanceVars.withIndex()) {
                if (i == 0 && sizeVar == null) {
                  sizeVar =
                    irInvoke(
                      dispatchReceiver = irGet(collectionProviderVar),
                      callee = metroSymbols.collectionSize,
                    )
                  continue
                }

                sizeVar =
                  irCallOp(
                    callee =
                      irBuiltIns.getBinaryOperator(
                        OperatorNameConventions.PLUS,
                        context.irBuiltIns.intType,
                        context.irBuiltIns.intType,
                      ),
                    dispatchReceiver = sizeVar!!,
                    argument =
                      irInvoke(
                        dispatchReceiver = irGet(collectionProviderVar),
                        callee = metroSymbols.collectionSize,
                      ),
                    type = irBuiltIns.intType,
                  )
              }
            }

            val lambda =
              irLambda(
                parent = parent,
                receiverParameter = irBuiltIns.mutableSetClass.typeWith(elementType),
                valueParameters = emptyList(),
                returnType = irBuiltIns.unitType,
                suspend = false,
              ) { function ->
                // This is the mutable set receiver
                val functionReceiver = function.extensionReceiverParameterCompat!!
                for (binding in individualProviders) {
                  +irInvoke(
                    dispatchReceiver = irGet(functionReceiver),
                    callee = metroSymbols.mutableSetAdd.symbol,
                    args =
                      listOf(
                        generateMultibindingArgument(
                          binding,
                          binding.contextualTypeKey,
                          fieldInitKey,
                          accessType = AccessType.INSTANCE,
                        )
                      ),
                  )
                }
                for (collectionProviderInstance in collectionProviderInstanceVars) {
                  +irInvoke(
                    dispatchReceiver = irGet(functionReceiver),
                    callee = metroSymbols.mutableSetAddAll.symbol,
                    args = listOf(irGet(collectionProviderInstance)),
                  )
                }
              }

            // If we have any collectionProviders, extract them to local vars first so we can
            // include their sizes into the call
            val args = listOf(sizeVar!!, lambda)
            +irInvoke(
              callee = callee,
              typeHint = binding.typeKey.type,
              typeArgs = listOf(elementType),
              args = args,
            )
          }
        }
      }
    }

  private fun generateMapKeyLiteral(binding: IrBinding): IrExpression {
    val mapKey =
      binding.typeKey.multibindingKeyData?.mapKey?.ir
        ?: reportCompilerBug("Unsupported multibinding source: $binding")
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
    sourceBindings: List<IrBinding>,
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
          arguments[0] = irInt(sourceBindings.size)
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
              for (binding in sourceBindings) {
                +irInvoke(
                  dispatchReceiver = irGet(functionReceiver),
                  callee = metroSymbols.mutableMapPut.symbol,
                  args =
                    listOf(
                      generateMapKeyLiteral(binding),
                      generateMultibindingArgument(
                        binding,
                        // Use the same context but with this binding's type key (which will have
                        // its MultibindingElement qualifier key)
                        originalValueContextKey.withIrTypeKey(binding.typeKey),
                        fieldInitKey,
                        accessType = valueAccessType,
                      ),
                    ),
                )
              }
            }
        }
    }

  context(scope: IrBuilderWithScope)
  private fun generateSetFactoryExpression(
    elementType: IrType,
    collectionProviders: List<IrBinding>,
    individualProviders: List<IrBinding>,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      /*
        val builder = SetFactory.<String>builder(1, 1)
        builder.addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
        builder.addCollectionProvider(provideString2Provider)
        return builder.build()
      */

      // Used to unpack the right provider type
      val valueProviderSymbols = metroSymbols.providerSymbolsFor(elementType)
      val providerClass =
        valueProviderSymbols.setFactoryBuilderAddProviderFunction.owner.regularParameters[0]
          .type
          .classOrFail
          .owner

      val resultType =
        irBuiltIns.setClass.typeWith(elementType).wrapInProvider(metroSymbols.metroProvider)

      return irBlock(resultType = resultType) {
        // val builder = SetFactory.<String>builder(1, 1)
        val builder =
          createAndAddTemporaryVariable(
            irInvoke(
              callee = valueProviderSymbols.setFactoryBuilderFunction,
              typeHint = valueProviderSymbols.setFactoryBuilder.typeWith(elementType),
              typeArgs = listOf(elementType),
              args = listOf(irInt(individualProviders.size), irInt(collectionProviders.size)),
            ),
            nameHint = "builder",
          )

        // builder.addProvider(...)
        for (provider in individualProviders) {
          +irInvoke(
            dispatchReceiver = irGet(builder),
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

        // builder.addCollectionProvider(...)
        for (provider in collectionProviders) {
          +irInvoke(
            dispatchReceiver = irGet(builder),
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

        // builder.build()
        val instance =
          irInvoke(
            dispatchReceiver = irGet(builder),
            callee = valueProviderSymbols.setFactoryBuilderBuildFunction,
            typeHint = resultType,
          )
        +with(metroSymbols.providerTypeConverter) {
          instance.convertTo(
            IrContextualTypeKey(IrTypeKey(irBuiltIns.setClass.typeWith(elementType)))
              .wrapInProvider()
          )
        }
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
      /*
        val builder = MapFactory.<Integer, Integer>builder(2)
        builder.put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
        builder.put(2, provideMapInt2Provider)
        builder.build()

        val builder = MapProviderFactory.<Integer, Integer>builder(2)
        builder.put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
        builder.put(2, provideMapInt2Provider)
        builder.build()
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

      if (size == 0) {
        return generateEmptyMapExpression(
          keyType,
          rawValueType,
          mapProviderType,
          valueIsWrappedInProvider,
          valueProviderSymbols,
          accessType,
        )
      }

      val sourceBindings =
        binding.sourceBindings.map { sourceKey -> bindingGraph.requireBinding(sourceKey) }

      val instance =
        if (accessType == AccessType.INSTANCE) {
          // Multiple elements but only needs a Map<Key, Value> type
          // Even if the value type is Provider<Value>, we'll denote it with `valueAccessType`
          return generateMapBuilderExpression(
            sourceBindings = sourceBindings,
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

          // .build()
          val buildFunction =
            if (valueIsWrappedInProvider) {
              valueProviderSymbols.mapProviderFactoryBuilderBuildFunction
            } else {
              valueProviderSymbols.mapFactoryBuilderBuildFunction
            }

          val resultType =
            valueProviderSymbols.canonicalProviderType.typeWithArguments(
              mapProviderType.requireSimpleType().arguments
            )

          irBlock(resultType = resultType) {
            // MapFactory.<Integer, Integer>builder(2)
            // MapProviderFactory.<Integer, Integer>builder(2)
            val builder =
              createAndAddTemporaryVariable(
                irInvoke(
                  callee = builderFunction,
                  typeArgs = listOf(keyType, valueType),
                  typeHint = builderType.typeWith(keyType, valueType),
                  args = listOf(irInt(size)),
                ),
                nameHint = "builder",
              )

            // .put(key, provider) for each binding
            for (sourceBinding in sourceBindings) {
              val providerTypeMetadata = sourceBinding.contextualTypeKey

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

              // Ensure we match the expected parameter type of the put() function we're calling
              val providerType = putter.owner.nonDispatchParameters[1].type.rawType()
              +irInvoke(
                dispatchReceiver = irGet(builder),
                callee = putter,
                typeHint = builder.type,
                args =
                  listOf(
                    generateMapKeyLiteral(sourceBinding),
                    generateMultibindingArgument(
                      sourceBinding,
                      originalValueContextKey.wrapInProvider(providerType)
                        .withIrTypeKey(sourceBinding.typeKey),
                      fieldInitKey,
                      accessType = AccessType.PROVIDER,
                    ),
                  ),
              )
            }

            // .build()
            +irInvoke(
              dispatchReceiver = irGet(builder),
              callee = buildFunction,
              typeHint = resultType,
            )
          }
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
      val kvArgs = listOf(keyType, rawValueType)
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
            typeHint = emptyCallee.owner.returnType.rawType().typeWith(kvArgs),
            typeArgs = kvArgs,
          )
        } else {
          // Call builder().build()
          irInvoke(
            // build()
            callee = valueFrameworkSymbols.mapProviderFactoryBuilderBuildFunction,
            typeHint =
              valueFrameworkSymbols.mapProviderFactoryBuilderBuildFunction.owner.returnType
                .rawType()
                .typeWith(kvArgs),
            dispatchReceiver =
              irInvoke(
                // builder()
                callee = valueFrameworkSymbols.mapProviderFactoryBuilderFunction,
                typeHint = mapProviderType,
                typeArgs = kvArgs,
                args = listOf(irInt(0)),
              ),
          )
        }
      } else {
        // Type: Provider<Map<Key, Value>>
        // Returns: MapFactory.empty()
        irInvoke(
          callee = valueFrameworkSymbols.mapFactoryEmptyFunction,
          typeHint =
            valueFrameworkSymbols.mapFactoryEmptyFunction.owner.returnType
              .rawType()
              .typeWith(kvArgs),
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
      return parentGenerator
        .generateBindingCode(
          provider,
          contextKey,
          accessType = accessType,
          fieldInitKey = fieldInitKey,
        )
        .letIf(accessType == AccessType.PROVIDER) {
          // If it's a provider, we need to handle the type of provider including interop
          typeAsProviderArgument(
            contextKey = contextKey,
            bindingCode = it,
            isAssisted = false,
            isGraphInstance = false,
          )
        }
    }
}
