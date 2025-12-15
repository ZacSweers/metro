// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.fields
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

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

@OptIn(DeprecatedForRemovalCompilerApi::class)
private class CircuitIrTransformer(
  private val pluginContext: IrPluginContext,
  private val symbols: CircuitSymbols.Ir,
) : IrElementTransformerVoid() {

  override fun visitConstructor(declaration: IrConstructor): IrStatement {
    if (declaration.origin == CircuitOrigins.IrFactoryConstructor && declaration.body == null) {
      declaration.apply { body = context(pluginContext) { generateDefaultConstructorBody() } }
    }
    return super.visitConstructor(declaration)
  }

  override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
    if (declaration.origin == CircuitOrigins.IrFactoryCreateFunction && declaration.body == null) {
      declaration.body = generateCreateFunctionBody(pluginContext, declaration)
    }
    return super.visitSimpleFunction(declaration)
  }

  private fun generateCreateFunctionBody(
    pluginContext: IrPluginContext,
    function: IrSimpleFunction,
  ): IrBody {
    val factoryClass = function.parentAsClass
    val factoryType = determineFactoryType(factoryClass)
    val screenParam = function.valueParameters.first { it.name == CircuitNames.screen }

    // Find the target info from the factory class annotations
    val targetInfo = extractTargetInfo(factoryClass)

    val returnType =
      when (factoryType) {
        FactoryType.UI -> symbols.ui.typeWith(pluginContext.irBuiltIns.anyNType).makeNullable()
        FactoryType.PRESENTER ->
          symbols.presenter.typeWith(pluginContext.irBuiltIns.anyNType).makeNullable()
      }

    return pluginContext.createIrBuilder(function.symbol).irBlockBody {
      // If we couldn't extract target info, just return null
      if (targetInfo == null) {
        +irReturn(irNull())
        return@irBlockBody
      }

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
      irCall(pluginContext.irBuiltIns.eqeqSymbol).apply {
        putValueArgument(0, irGet(screenParam))
        putValueArgument(1, irGetObject(screenClassSymbol))
      }
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
      for (param in createFunction.valueParameters) {
        val matchingParam = function.valueParameters.find { it.name == param.name }
        if (matchingParam != null) {
          putValueArgument(argIndex++, irGet(matchingParam))
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

  // TODO make this return non-null
  private fun extractTargetInfo(factoryClass: IrClass): TargetInfo? {
    // Try nested factory first (parent is a class with @CircuitInject)
    val parentClass = factoryClass.parent as? IrClass
    if (parentClass != null) {
      val circuitInjectAnnotation =
        parentClass.getAnnotation(CircuitClassIds.CircuitInject.asSingleFqName())
      if (circuitInjectAnnotation != null) {
        return extractScreenFromAnnotation(circuitInjectAnnotation)
      }
    }

    // Top-level function factory: derive function name from factory class name
    // e.g., "HomePresenterFactory" -> "homePresenter"
    val factoryName = factoryClass.name.asString()
    if (!factoryName.endsWith("Factory")) return null

    val functionName = factoryName.removeSuffix("Factory").decapitalizeUS()
    val packageFqName = factoryClass.getPackageFragment().packageFqName
    val callableId = CallableId(packageFqName, Name.identifier(functionName))

    val functionSymbol = pluginContext.referenceFunctions(callableId).singleOrNull() ?: return null
    val circuitInjectAnnotation =
      functionSymbol.owner.getAnnotation(CircuitClassIds.CircuitInject.asSingleFqName())
        ?: return null

    return extractScreenFromAnnotation(circuitInjectAnnotation)
  }

  private fun extractScreenFromAnnotation(circuitInjectAnnotation: IrConstructorCall): TargetInfo? {
    // First argument is the screen class (CircuitInject(screen = ..., scope = ...))
    val screenArg = circuitInjectAnnotation.getValueArgument(0) ?: return null
    val screenClassRef = screenArg.expectAsOrNull<IrClassReference>() ?: return null
    val screenClassSymbol = screenClassRef.classType.classOrNull ?: return null
    return TargetInfo(
      screenClassSymbol = screenClassSymbol,
      screenIsObject = screenClassSymbol.owner.kind == ClassKind.OBJECT,
    )
  }

  private data class TargetInfo(val screenClassSymbol: IrClassSymbol, val screenIsObject: Boolean)
}
