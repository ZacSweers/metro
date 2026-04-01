// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.abstractFunctions
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.kClassReference
import dev.zacsweers.metro.compiler.ir.regularParameters
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEqeqeq
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.fields
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * IR extension that implements constructor and `create()` method bodies for Circuit-generated
 * factories.
 *
 * This extension should run after the Compose compiler IR plugin.
 */
public class CircuitIrExtension : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = CircuitSymbols.Ir(pluginContext)
    moduleFragment.transformChildrenVoid(CircuitIrTransformer(pluginContext, symbols))
  }
}

private class CircuitIrTransformer(
  private val pluginContext: IrPluginContext,
  private val symbols: CircuitSymbols.Ir,
) : IrElementTransformerVoid() {

  override fun visitClass(declaration: IrClass): IrStatement {
    if (
      declaration.origin.expectAsOrNull<IrDeclarationOrigin.GeneratedByPlugin>()?.pluginKey
        is CircuitOrigins.FactoryClass
    ) {
      // Find the target info from the factory class annotations
      val circuitTargetInfo = declaration.circuitFactoryTargetData!!
      val screenClass = pluginContext.referenceClass(circuitTargetInfo.screenType)!!

      // Add an @Origin annotation, because we can't add this in FIR safely
      circuitTargetInfo.originClassId?.let { originClassId ->
        pluginContext.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
          declaration,
          context(pluginContext) {
            buildAnnotation(declaration.symbol, symbols.originAnnotationCtor) {
              it.arguments[0] = kClassReference(pluginContext.referenceClass(originClassId)!!)
            }
          },
        )
      }

      val createFunction = declaration.abstractFunctions().first { it.name.asString() == "create" }
      createFunction.isFakeOverride = false
      createFunction.modality = Modality.FINAL
      createFunction.body = generateCreateFunctionBody(createFunction, screenClass)
    }
    return super.visitClass(declaration)
  }

  private fun generateCreateFunctionBody(
    function: IrSimpleFunction,
    screenClass: IrClassSymbol,
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
              // Main branch: when screen matches
              irBranch(
                generateScreenMatchCondition(screenParam, targetInfo),
                generateInstantiationExpression(function, factoryClass, targetInfo),
              ),
              // Else branch: return null
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
  ): IrExpression {
    // For now, generate a simple provider() call if there's a provider field
    // or return null for more complex cases that need more implementation

    val providerField = factoryClass.fields.find { it.name == CircuitNames.provider }
    val factoryField = factoryClass.fields.find { it.name == CircuitNames.factoryField }

    return when {
      providerField != null -> {
        // provider() - call invoke on the provider
        generateProviderCall(function, providerField)
      }
      factoryField != null -> {
        // factory.create(...) - call the assisted factory
        generateAssistedFactoryCall(function, factoryField, targetInfo)
      }
      else -> {
        // For function-based factories, we would need to call presenterOf{} or ui{}
        // This is more complex and requires access to the original function
        // For now, return null as a placeholder
        irNull()
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

      // Pass through screen, navigator, context as needed
      var argIndex = 0
      for (param in createFunction.regularParameters) {
        val matchingParam = function.regularParameters.find { it.name == param.name }
        if (matchingParam != null) {
          arguments[argIndex++] = irGet(matchingParam)
        }
      }
    }
  }

  private fun determineFactoryType(factoryClass: IrClass): FactoryType {
    return when {
      factoryClass.superTypes.any {
        it.classOrNull?.owner?.name?.asString() == "Factory" &&
          it.classOrNull?.owner?.parent?.let { parent ->
            (parent as? IrClass)?.name?.asString() == "Ui"
          } == true
      } -> FactoryType.UI
      factoryClass.superTypes.any {
        it.classOrNull?.owner?.name?.asString() == "Factory" &&
          it.classOrNull?.owner?.parent?.let { parent ->
            (parent as? IrClass)?.name?.asString() == "Presenter"
          } == true
      } -> FactoryType.PRESENTER
      else -> FactoryType.UI // Default fallback
    }
  }

  @JvmInline
  private value class TargetInfo(val screenClassSymbol: IrClassSymbol) {
    val screenIsObject: Boolean
      get() = screenClassSymbol.owner.kind == ClassKind.OBJECT
  }
}
