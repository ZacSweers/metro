// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.applyIf
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.annotationClass
import dev.zacsweers.metro.compiler.ir.copyParameterDefaultValues
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.hasMetroDefault
import dev.zacsweers.metro.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.setExtensionReceiver
import dev.zacsweers.metro.compiler.ir.stripOuterProviderOrLazy
import dev.zacsweers.metro.compiler.ir.stubExpression
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.mirrorIrConstructorCalls
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWithParameters
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyParametersFrom
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentAsClass

/**
 * Implement a static `create()` function for a given [targetConstructor].
 *
 * ```kotlin
 * // Simple
 * fun create(valueProvider: Provider<String>): Example_Factory = Example_Factory(valueProvider)
 *
 * // Generic
 * fun <T> create(valueProvider: Provider<T>): Example_Factory<T> = Example_Factory<T>(valueProvider)
 * ```
 */
@IgnorableReturnValue
context(context: IrMetroContext)
internal fun generateStaticCreateFunction(
  objectClassToGenerateIn: IrClass,
  factoryClass: IrClass,
  sourceTypeParameters: IrClass,
  returnTypeProvider: (List<IrTypeParameter>) -> IrType, // Not called if assisted inject
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters,
  sourceFunction: IrFunction?,
  patchCreationParams: Boolean = true,
  isAssistedInject: Boolean = false,
): IrSimpleFunction {
  val createFunction =
    objectClassToGenerateIn
      .addFunction(
        name = Symbols.StringNames.CREATE,
        // Placeholder, replaced in body
        returnType = context.irBuiltIns.unitType,
        origin = Origins.FactoryCreateFunction,
      )
      .apply {
        val typeParams = copyTypeParametersFrom(sourceTypeParameters)
        this.returnType =
          if (isAssistedInject) {
            factoryClass.symbol.typeWithParameters(typeParams)
          } else {
            returnTypeProvider(typeParams)
          }
        val typeRemapper =
          sourceTypeParameters.deepRemapperFor(
            sourceTypeParameters.symbol.typeWithParameters(typeParams)
          )
        addParameters(
          parameters.allParameters.filterNot { it.isAssisted },
          wrapInProvider = true,
          copyQualifiers = true,
          typeRemapper = { type -> typeRemapper.remapType(type) },
        )
        context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(this)
      }
  transformStaticCreateFunction(
    factoryClass = factoryClass,
    targetConstructor = targetConstructor,
    parameters = parameters,
    providerFunction = sourceFunction,
    patchCreationParams = patchCreationParams,
    copyQualifiers = false, // We've already done it
    createFunction = createFunction,
  )
  return createFunction
}

@IgnorableReturnValue
context(context: IrMetroContext)
internal fun transformStaticCreateFunction(
  objectClassToGenerateIn: IrClass,
  factoryClass: IrClass,
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters,
  providerFunction: IrFunction?,
  patchCreationParams: Boolean = true,
  copyQualifiers: Boolean = false,
): IrSimpleFunction {
  val createFunction =
    objectClassToGenerateIn.functions.first { it.origin == Origins.FactoryCreateFunction }
  transformStaticCreateFunction(
    factoryClass = factoryClass,
    targetConstructor = targetConstructor,
    parameters = parameters,
    providerFunction = providerFunction,
    patchCreationParams = patchCreationParams,
    copyQualifiers = copyQualifiers,
    createFunction = createFunction,
  )
  return createFunction
}

context(context: IrMetroContext)
private fun transformStaticCreateFunction(
  factoryClass: IrClass,
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters,
  providerFunction: IrFunction?,
  patchCreationParams: Boolean, // TODO eventually move this to function creation
  copyQualifiers: Boolean,
  createFunction: IrSimpleFunction,
) {
  createFunction.apply {
    if (patchCreationParams) {
      val instanceParam = regularParameters.find { it.origin == Origins.InstanceParameter }
      val valueParamsToPatch =
        nonDispatchParameters.filter { it.origin == Origins.RegularParameter }
      copyParameterDefaultValues(
        providerFunction = providerFunction,
        sourceMetroParameters = parameters,
        sourceParameters =
          parameters.nonDispatchParameters
            .filterNot { it.isAssisted || it.ir?.origin == Origins.InstanceParameter }
            .map { it.asValueParameter },
        targetParameters = valueParamsToPatch,
        containerParameter = instanceParam,
        wrapInProvider = true,
      )
    }

    // Copy qualifier annotations from source parameters to function parameters
    if (copyQualifiers) {
      for ((i, param) in regularParameters.withIndex()) {
        val sourceParam = parameters.regularParameters[i]
        sourceParam.typeKey.qualifier?.let { qualifier ->
          context.pluginContext.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
            param,
            qualifier.ir.deepCopyWithSymbols(),
          )
        }
      }
    }

    body =
      context.createIrBuilder(symbol).run {
        irExprBodySafe(
          if (factoryClass.isObject) {
            irGetObject(factoryClass.symbol)
          } else {
            irCallConstructorWithSameParameters(createFunction, targetConstructor)
          }
        )
      }
  }
}

/**
 * Generates a static `newInstance()` function into a given [parentClass].
 *
 * ```
 * // Simple
 * fun newInstance(value: T): Example = Example(value)
 *
 * // Generic
 * fun <T> newInstance(value: T): Example<T> = Example<T>(value)
 *
 * // Provider
 * fun newInstance(value: Provider<String>): Example = Example(value)
 * ```
 */
context(context: IrMetroContext)
internal fun generateStaticNewInstanceFunction(
  parentClass: IrClass,
  sourceTypeParameters: IrClass,
  returnTypeProvider: (List<IrTypeParameter>) -> IrType,
  sourceMetroParameters: Parameters,
  sourceParameters: List<IrValueParameter>,
  functionName: String = Symbols.StringNames.NEW_INSTANCE,
  targetFunction: IrFunction? = null,
  buildBody: IrBuilderWithScope.(IrSimpleFunction) -> IrExpression,
): IrSimpleFunction {
  val newInstanceFunction =
    parentClass
      .addFunction(
        name = functionName,
        // Placeholder, replaced in body
        returnType = context.irBuiltIns.unitType,
        origin = Origins.FactoryNewInstanceFunction,
      )
      .apply {
        val typeParams = copyTypeParametersFrom(sourceTypeParameters)
        this.returnType = returnTypeProvider(typeParams)
        val typeRemapper =
          sourceTypeParameters.deepRemapperFor(
            sourceTypeParameters.symbol.typeWithParameters(typeParams)
          )
        addParameters(
          sourceMetroParameters.allParameters,
          wrapInProvider = false,
          copyQualifiers = true,
          typeRemapper = { type -> typeRemapper.remapType(type) },
        )
        context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(this)
      }
  transformStaticNewInstanceFunction(
    sourceMetroParameters = sourceMetroParameters,
    sourceParameters = sourceParameters,
    targetFunction = targetFunction,
    newInstanceFunction = newInstanceFunction,
    buildBody = buildBody,
  )
  return newInstanceFunction
}

context(context: IrMetroContext)
internal fun transformStaticNewInstanceFunction(
  parentClass: IrClass,
  sourceMetroParameters: Parameters,
  sourceParameters: List<IrValueParameter>,
  targetFunction: IrFunction? = null,
  buildBody: IrBuilderWithScope.(IrSimpleFunction) -> IrExpression,
): IrSimpleFunction {
  val newInstanceFunction =
    parentClass.functions.first { it.origin == Origins.FactoryNewInstanceFunction }
  transformStaticNewInstanceFunction(
    sourceMetroParameters = sourceMetroParameters,
    sourceParameters = sourceParameters,
    targetFunction = targetFunction,
    newInstanceFunction = newInstanceFunction,
    buildBody = buildBody,
  )
  return newInstanceFunction
}

context(context: IrMetroContext)
private fun transformStaticNewInstanceFunction(
  sourceMetroParameters: Parameters,
  sourceParameters: List<IrValueParameter>,
  targetFunction: IrFunction?,
  newInstanceFunction: IrSimpleFunction,
  buildBody: IrBuilderWithScope.(IrSimpleFunction) -> IrExpression,
) {
  newInstanceFunction.apply {
    val instanceParam = regularParameters.find { it.origin == Origins.InstanceParameter }
    val valueParametersToMap =
      nonDispatchParameters.filter { it.origin == Origins.RegularParameter }
    // TODO move to function creation
    copyParameterDefaultValues(
      providerFunction = targetFunction,
      sourceMetroParameters = sourceMetroParameters,
      sourceParameters = sourceParameters,
      targetParameters = valueParametersToMap,
      containerParameter = instanceParam,
    )

    body = context.createIrBuilder(symbol).run { irExprBodySafe(buildBody(this@apply)) }
  }
}

/**
 * Generates a metadata-visible function in the factory class that matches the signature of the
 * target function. This function is used in downstream compilations to read the function's
 * signature and also dirty IC.
 */
context(context: IrMetroContext)
internal fun generateMetadataVisibleMirrorFunction(
  factoryClass: IrClass,
  target: IrFunction?,
  backingField: IrField?,
  annotations: MetroAnnotations<IrAnnotation>,
): IrSimpleFunction {
  val returnType =
    target?.returnType
      ?: backingField?.type
      ?: error("Either target or backingField must be non-null")
  val function =
    factoryClass
      .addFunction {
        name = Symbols.Names.mirrorFunction
        this.returnType = returnType
      }
      .apply {
        if (target is IrConstructor) {
          val sourceClass = factoryClass.parentAsClass
          val scopeAndQualifierAnnotations = buildList {
            val classMetroAnnotations = sourceClass.metroAnnotations(context.metroSymbols.classIds)
            classMetroAnnotations.scope?.ir?.let(::add)
            classMetroAnnotations.qualifier?.ir?.let(::add)
          }
          if (scopeAndQualifierAnnotations.isNotEmpty()) {
            val container =
              object : IrAnnotationContainer {
                override val annotations: List<IrConstructorCall> = scopeAndQualifierAnnotations
              }
            copyAnnotationsFrom(container)
          }
          copyTypeParametersFrom(sourceClass)
        } else {
          // Copy type parameters from the factory class (e.g., generic binding containers)
          copyTypeParametersFrom(factoryClass)

          // If it's a regular (provides) function or backing field, just always copy its
          // annotations
          this.annotations =
            annotations
              .mirrorIrConstructorCalls(symbol)
              .filterNot {
                // Exclude @Provides to avoid reentrant factory gen
                it.annotationClass.classId in context.metroSymbols.classIds.providesAnnotations
              }
              .map { it.deepCopyWithSymbols() }
        }
        if (target != null) {
          copyParametersFrom(target)
          target.extensionReceiverParameterCompat?.let { setExtensionReceiver(it.copyTo(this)) }
        }
        setDispatchReceiver(factoryClass.thisReceiverOrFail.copyTo(this))

        regularParameters.forEach {
          // If it has a default value expression, just replace it with a stub. We don't need it to
          // be functional, we just need it to be indicated
          if (it.hasMetroDefault()) {
            it.defaultValue = context.createIrBuilder(symbol).run { irExprBody(stubExpression()) }
          } else {
            it.defaultValue = null
          }
        }
        // The function's signature already matches the target function's signature, all we need
        // this for
        body = context.createIrBuilder(symbol).run { irExprBodySafe(stubExpression()) }
      }
  context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
  return function
}

context(context: IrMetroContext)
internal fun IrFunction.addParameters(
  params: List<Parameter>,
  wrapInProvider: Boolean,
  copyQualifiers: Boolean = false,
  typeRemapper: ((IrType) -> IrType)? = null,
  onParam: (IrTypeKey, IrValueParameter) -> Unit = { _, _ -> },
) {
  for (param in params) {
    val isInstanceParam = param.asValueParameter.kind == IrParameterKind.DispatchReceiver
    val baseType =
      if (wrapInProvider && !isInstanceParam) {
        // Strip all outer Provider/Lazy layers (e.g. Provider<Lazy<T>> → T) but preserve
        // inner structure like Map<K, Provider<V>>, then wrap in a single Provider.
        var stripped = param.contextualTypeKey
        while (stripped.isWrapped) {
          stripped = stripped.stripOuterProviderOrLazy()
        }
        stripped.wrapInProvider().toIrType()
      } else {
        param.contextualTypeKey.toIrType()
      }
    addValueParameter(
        name =
          if (isInstanceParam) {
            Symbols.Names.instance
          } else {
            param.name
          },
        type = typeRemapper?.invoke(baseType) ?: baseType,
        origin =
          if (isInstanceParam) {
            Origins.InstanceParameter
          } else {
            Origins.RegularParameter
          },
      )
      .applyIf(copyQualifiers) {
        param.typeKey.qualifier?.let { annotations += it.ir.deepCopyWithSymbols() }
      }
      .also { onParam(param.typeKey, it) }
  }
}
