// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.abstractFunctions
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.kClassReference
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.wasm.ir2wasm.allSuperInterfaces
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEqeqeq
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * IR extension that implements constructor and `create()` method bodies for Circuit-generated
 * factories.
 *
 * This extension should run after the Compose compiler IR plugin.
 */
public class CircuitIrExtension(private val compatContext: CompatContext) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = CircuitSymbols.Ir(pluginContext)
    moduleFragment.transformChildrenVoid(
      CircuitIrTransformer(pluginContext, symbols, compatContext)
    )
  }
}

private class CircuitIrTransformer(
  private val pluginContext: IrPluginContext,
  private val symbols: CircuitSymbols.Ir,
  private val compatContext: CompatContext,
) : IrElementTransformerVoid(), CompatContext by compatContext {

  private val metadataDeclarationRegistrarCompat: IrGeneratedDeclarationsRegistrarCompat by lazy {
    compatContext.createIrGeneratedDeclarationsRegistrar(pluginContext)
  }

  override fun visitClass(declaration: IrClass): IrStatement {
    if (
      declaration.origin.expectAsOrNull<IrDeclarationOrigin.GeneratedByPlugin>()?.pluginKey
        is CircuitOrigins.FactoryClass
    ) {
      // Find the target info from the factory class annotations
      val circuitTargetInfo = declaration.circuitFactoryTargetData!!
      val screenClass = pluginContext.referenceClass(circuitTargetInfo.screenType)!!

      // Add an @Origin annotation, because we can't add this in FIR safely due to phase issues
      circuitTargetInfo.originClassId?.let { originClassId ->
        metadataDeclarationRegistrarCompat.addMetadataVisibleAnnotationsToElement(
          declaration,
          context(pluginContext) {
            buildAnnotation(declaration.symbol, symbols.originAnnotationCtor) {
              it.arguments[0] = kClassReference(pluginContext.referenceClass(originClassId)!!)
            }
          },
        )
      }

      val fieldsByName = addBackingFieldsForConstructorParams(declaration)

      val createFunction = declaration.abstractFunctions().first { it.name.asString() == "create" }

      // Properly finalize the fake override with a dispatch receiver scoped to this function
      createFunction.finalizeFakeOverride(declaration.thisReceiver!!)
      createFunction.modality = Modality.FINAL
      createFunction.body = generateCreateFunctionBody(createFunction, screenClass, fieldsByName)
    }
    return super.visitClass(declaration)
  }

  private fun generateCreateFunctionBody(
    function: IrSimpleFunction,
    screenClass: IrClassSymbol,
    fieldsByName: Map<Name, IrField>,
  ): IrBody {
    val factoryClass = function.parentAsClass
    val factoryType = determineFactoryType(factoryClass)
    val screenParam = function.regularParameters.first { it.name == CircuitNames.screen }
    val targetInfo = TargetInfo(screenClass)

    val returnType =
      when (factoryType) {
        FactoryType.UI -> symbols.ui.typeWith(pluginContext.irBuiltIns.anyNType).makeNullable()
        FactoryType.PRESENTER ->
          symbols.presenter.typeWith(pluginContext.irBuiltIns.anyNType).makeNullable()
      }

    return pluginContext.createIrBuilder(function.symbol).irBlockBody {
      +irReturn(
        irWhen(
          returnType,
          branches =
            listOf(
              irBranch(
                generateScreenMatchCondition(screenParam, targetInfo),
                generateInstantiationExpression(function, factoryClass, targetInfo, fieldsByName),
              ),
              irElseBranch(irNull()),
            ),
        )
      )
    }
  }

  private fun IrBuilderWithScope.generateScreenMatchCondition(
    screenParam: IrValueParameter,
    targetInfo: TargetInfo,
  ): IrExpression {
    val screenClassSymbol = targetInfo.screenClassSymbol

    return if (screenClassSymbol.owner.kind == ClassKind.OBJECT) {
      // For object screens, use equality check: screen == ScreenObject
      irEqeqeq(irGet(screenParam), irGetObject(screenClassSymbol))
    } else {
      // For class screens, use is check: screen is ScreenClass
      irIs(irGet(screenParam), screenClassSymbol.starProjectedType)
    }
  }

  private fun IrBuilderWithScope.generateInstantiationExpression(
    function: IrSimpleFunction,
    factoryClass: IrClass,
    targetInfo: TargetInfo,
    fieldsByName: Map<Name, IrField>,
  ): IrExpression {
    val providerField = fieldsByName[CircuitNames.provider]
    val factoryField = fieldsByName[CircuitNames.factoryField]

    val circuitTargetInfo = factoryClass.circuitFactoryTargetData!!

    return when {
      providerField != null -> {
        // provider() - call invoke on the provider
        generateProviderCall(function, providerField)
      }
      factoryField != null -> {
        // factory.create(...) - call the assisted factory
        generateAssistedFactoryCall(function, factoryField, targetInfo)
      }
      circuitTargetInfo.instantiationType == InstantiationType.FUNCTION -> {
        // Function-based factory: call presenterOf{}/ui{}
        val firFunctionSymbol =
          circuitTargetInfo.originalFunctionSymbol
            ?: error("Function-based factory missing original function symbol")
        val factoryType = determineFactoryType(factoryClass)
        generateFunctionFactoryCall(function, factoryType, firFunctionSymbol, fieldsByName)
      }
      else -> {
        // Class-based factory with no constructor params (e.g., no-injection presenter/ui).
        // Instantiate the target class directly.
        val targetClassId =
          circuitTargetInfo.originClassId ?: error("Class-based factory missing origin class ID")
        val targetClass =
          pluginContext.referenceClass(targetClassId)
            ?: error("Could not find target class: $targetClassId")
        irCall(targetClass.constructors.first())
      }
    }
  }

  private fun IrBuilderWithScope.generateProviderCall(
    function: IrSimpleFunction,
    providerField: IrField,
  ): IrExpression {
    val thisReceiver = function.dispatchReceiverParameter ?: return irNull()

    // Get the provider field and call invoke()
    val providerGet = irGetField(irGet(thisReceiver), providerField)

    // Find the invoke function on Provider
    val providerClass = providerField.type.classOrNull?.owner ?: return irNull()
    val invokeFunction =
      providerClass.functions.find { it.name.asString() == "invoke" } ?: return irNull()

    return irCall(invokeFunction).apply { dispatchReceiver = providerGet }
  }

  private fun IrBuilderWithScope.generateAssistedFactoryCall(
    function: IrSimpleFunction,
    factoryField: IrField,
    @Suppress("UNUSED_PARAMETER") targetInfo: TargetInfo,
  ): IrExpression {
    val thisReceiver = function.dispatchReceiverParameter ?: return irNull()

    // Get the factory field
    val factoryGet = irGetField(irGet(thisReceiver), factoryField)

    // Find the create function on the factory
    val factoryClass = factoryField.type.classOrNull?.owner ?: return irNull()
    val createFunction =
      factoryClass.functions.find {
        it.name.asString() == "create" || it.name.asString() == "invoke"
      } ?: return irNull()

    // Build the call with assisted parameters
    return irCall(createFunction).apply {
      dispatchReceiver = factoryGet

      // Pass through screen, navigator, context as needed, using indexInParameters
      // to correctly skip the dispatch receiver slot
      for (param in createFunction.regularParameters) {
        val matchingParam = function.regularParameters.find { it.name == param.name }
        if (matchingParam != null) {
          arguments[param.indexInParameters] = irGet(matchingParam)
        }
      }
    }
  }

  /**
   * For function-based factories, the FIR extension generates constructor params (Provider<T>) for
   * injected dependencies but doesn't create backing fields. We add them here so the lambda body in
   * `create()` can read the values via `irGetField`.
   */
  private fun addBackingFieldsForConstructorParams(factoryClass: IrClass): Map<Name, IrField> {
    val constructor = factoryClass.constructors.firstOrNull() ?: return emptyMap()
    val constructorParams = constructor.regularParameters
    if (constructorParams.isEmpty()) return emptyMap()

    val result = mutableMapOf<Name, IrField>()
    for (param in constructorParams) {
      val field = factoryClass.addField(param.name, param.type)
      field.initializer =
        pluginContext.createIrBuilder(field.symbol).run { irExprBody(irGet(param)) }
      result[param.name] = field
    }
    return result
  }

  /**
   * Generates the instantiation expression for function-based factories by calling `presenterOf {
   * originalFunction(...) }` or `ui<State> { state, modifier -> originalFunction(...) }`.
   *
   * The lambda is annotated with `@Composable` so the Compose compiler transforms it.
   */
  private fun IrBuilderWithScope.generateFunctionFactoryCall(
    createFunction: IrSimpleFunction,
    factoryType: FactoryType,
    firFunctionSymbol: FirFunctionSymbol<*>,
    fieldsByName: Map<Name, IrField>,
  ): IrExpression {
    // Look up the IR function by matching against the FIR symbol stored in the target.
    // We filter out `expect` declarations in FIR, so we should only see actual functions here.
    val originalFunctionSymbol =
      pluginContext.referenceFunctions(firFunctionSymbol.callableId).first { irSymbol ->
        (irSymbol.owner.metadata as? FirMetadataSource.Function)?.fir?.symbol == firFunctionSymbol
      }

    val originalFunction = originalFunctionSymbol.owner
    // Build parameter mapping from create() params
    val availableValueParams = buildMap {
      for (param in createFunction.regularParameters) {
        put(param.name, param)
      }
    }

    // Resolve injected dependencies from factory fields. For plain Provider<T> fields,
    // extract the value via .invoke() outside the composable lambda to avoid recomputation
    // on every recomposition. For types already wrapped in Provider/Lazy/Function (which the
    // original function expects as-is), pass the field value directly to the lambda.
    return irBlock {
      val resolvedLocals = mutableMapOf<Name, IrValueDeclaration>()
      for (param in originalFunction.regularParameters) {
        if (param.name in availableValueParams) continue
        val field = fieldsByName[param.name] ?: continue
        val fieldGet = irGetField(irGet(createFunction.dispatchReceiverParameter!!), field)

        // Check if the original function param type is already Provider/Lazy/Function.
        // If so, the field type matches and we pass it through without invoking.
        val paramType = param.type
        val paramClassId = paramType.classOrNull?.owner?.classId
        val isAlreadyWrapped =
          paramClassId != null &&
            (paramClassId in Symbols.ClassIds.commonMetroProviders ||
              paramClassId == Symbols.ClassIds.Lazy ||
              paramClassId == Symbols.ClassIds.function0)

        val localVar =
          if (isAlreadyWrapped) {
            // Pass through as-is (the field type already matches the param type)
            irTemporary(fieldGet, nameHint = param.name.asString())
          } else {
            // Provider<T> field — extract via .invoke() once
            val providerClass = field.type.classOrNull?.owner ?: continue
            val invokeFunction =
              providerClass.functions.find { it.name.asString() == "invoke" } ?: continue
            val invokedValue = irCall(invokeFunction).apply { dispatchReceiver = fieldGet }
            irTemporary(invokedValue, nameHint = param.name.asString())
          }
        resolvedLocals[param.name] = localVar
      }

      // Merge all available params: create() params + resolved locals
      val allAvailableParams = buildMap {
        putAll(availableValueParams)
        putAll(resolvedLocals)
      }

      val factoryCall =
        when (factoryType) {
          FactoryType.PRESENTER -> {
            val stateType = originalFunction.returnType
            irCall(symbols.presenterOfFun).apply {
              typeArguments[0] = stateType
              arguments[0] =
                buildComposableLambda(
                  createFunction = createFunction,
                  originalFunction = originalFunction,
                  originalFunctionSymbol = originalFunctionSymbol,
                  returnType = stateType,
                  lambdaParamTypes = emptyList(),
                  capturedParams = allAvailableParams,
                )
            }
          }
          FactoryType.UI -> {
            val stateType =
              originalFunction.regularParameters
                .firstOrNull { param ->
                  param.type.classOrNull?.owner?.superTypes?.any {
                    it.classOrNull?.owner?.name?.asString() == "CircuitUiState"
                  } == true
                }
                ?.type ?: pluginContext.irBuiltIns.anyNType

            irCall(symbols.uiFun).apply {
              typeArguments[0] = stateType
              arguments[0] =
                buildComposableLambda(
                  createFunction = createFunction,
                  originalFunction = originalFunction,
                  originalFunctionSymbol = originalFunctionSymbol,
                  returnType = pluginContext.irBuiltIns.unitType,
                  lambdaParamTypes =
                    listOf(
                      CircuitNames.state to stateType,
                      CircuitNames.modifier to symbols.modifier.defaultType,
                    ),
                  capturedParams = allAvailableParams,
                )
            }
          }
        }
      +factoryCall
    }
  }

  /**
   * Builds a `@Composable` lambda that calls [originalFunction] with params matched by name from:
   * 1. The lambda's own params (e.g., state, modifier for UI)
   * 2. [capturedParams] — pre-resolved values from the `create()` scope (create() params + provider
   *    locals extracted before the lambda)
   */
  private fun buildComposableLambda(
    createFunction: IrSimpleFunction,
    originalFunction: IrSimpleFunction,
    originalFunctionSymbol: IrSimpleFunctionSymbol,
    returnType: IrType,
    lambdaParamTypes: List<Pair<Name, IrType>>,
    capturedParams: Map<Name, IrValueDeclaration>,
  ): IrFunctionExpression {
    // TODO irLambda
    val lambda =
      pluginContext.irFactory
        .buildFun {
          startOffset = SYNTHETIC_OFFSET
          endOffset = SYNTHETIC_OFFSET
          origin = Origins.FirstParty.LOCAL_FUNCTION_FOR_LAMBDA
          name = Name.special("<anonymous>")
          visibility = DescriptorVisibilities.LOCAL
          this.returnType = returnType
        }
        .apply {
          parent = createFunction

          // @Composable annotation so Compose compiler transforms this lambda
          annotations =
            listOf(
              pluginContext.createIrBuilder(symbol).run {
                irAnnotationCompat(symbols.composableAnnotationCtor, typeArguments = emptyList())
              }
            )

          for ((paramName, paramType) in lambdaParamTypes) {
            addValueParameter(paramName.asString(), paramType)
          }

          // Merge all available params: captured from create() scope + lambda's own params
          val allParams = buildMap {
            putAll(capturedParams)
            for (param in regularParameters) {
              put(param.name, param)
            }
          }

          body =
            pluginContext.createIrBuilder(symbol).irBlockBody {
              val call =
                irCall(originalFunctionSymbol).apply {
                  var argIndex = 0
                  for (param in originalFunction.regularParameters) {
                    arguments[argIndex++] = allParams[param.name]?.let { irGet(it) }
                  }
                }
              if (returnType == pluginContext.irBuiltIns.unitType) {
                +call
              } else {
                +irReturn(call)
              }
            }
        }

    return IrFunctionExpressionImpl(
      startOffset = SYNTHETIC_OFFSET,
      endOffset = SYNTHETIC_OFFSET,
      type =
        pluginContext.irBuiltIns
          .functionN(lambdaParamTypes.size)
          .typeWith(*(lambdaParamTypes.map { it.second } + returnType).toTypedArray()),
      origin = IrStatementOrigin.LAMBDA,
      function = lambda,
    )
  }

  private fun determineFactoryType(factoryClass: IrClass): FactoryType {
    for (supertype in factoryClass.allSuperInterfaces()) {
      return when (supertype.classId) {
        FactoryType.UI.factoryClassId -> FactoryType.UI
        FactoryType.PRESENTER.factoryClassId -> FactoryType.PRESENTER
        else -> continue
      }
    }
    error("Could not determine factory type for ${factoryClass.classId}")
  }

  @JvmInline
  private value class TargetInfo(val screenClassSymbol: IrClassSymbol) {
    val screenIsObject: Boolean
      get() = screenClassSymbol.owner.kind == ClassKind.OBJECT
  }
}
