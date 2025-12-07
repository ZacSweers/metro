// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.annotationClass
import dev.zacsweers.metro.compiler.ir.copyParameterDefaultValues
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.hasMetroDefault
import dev.zacsweers.metro.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.setExtensionReceiver
import dev.zacsweers.metro.compiler.ir.stubExpression
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.mirrorIrConstructorCalls
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
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
context(context: IrMetroContext)
internal fun generateStaticCreateFunction(
  parentClass: IrClass,
  targetClass: IrClass,
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters,
  providerFunction: IrFunction?,
  patchCreationParams: Boolean = true,
  copyQualifiers: Boolean = false,
): IrSimpleFunction {
  val function = parentClass.functions.first { it.origin == Origins.FactoryCreateFunction }

  return function.apply {
    if (patchCreationParams) {
      val instanceParam = regularParameters.find { it.origin == Origins.InstanceParameter }
      val valueParamsToPatch =
        nonDispatchParameters.filter { it.origin == Origins.RegularParameter }
      copyParameterDefaultValues(
        providerFunction = providerFunction,
        sourceMetroParameters = parameters,
        sourceParameters =
          parameters.nonDispatchParameters.filterNot { it.isAssisted }.map { it.asValueParameter },
        targetParameters = valueParamsToPatch,
        targetGraphParameter = instanceParam,
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
          if (targetClass.isObject) {
            irGetObject(targetClass.symbol)
          } else {
            irCallConstructorWithSameParameters(function, targetConstructor)
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
  sourceMetroParameters: Parameters,
  sourceParameters: List<IrValueParameter>,
  targetFunction: IrFunction? = null,
  buildBody: IrBuilderWithScope.(IrSimpleFunction) -> IrExpression,
): IrSimpleFunction {
  val function = parentClass.functions.first { it.origin == Origins.FactoryNewInstanceFunction }

  return function.apply {
    val instanceParam = regularParameters.find { it.origin == Origins.InstanceParameter }
    val valueParametersToMap =
      nonDispatchParameters.filter { it.origin == Origins.RegularParameter }
    copyParameterDefaultValues(
      providerFunction = targetFunction,
      sourceMetroParameters = sourceMetroParameters,
      sourceParameters = sourceParameters,
      targetParameters = valueParametersToMap,
      targetGraphParameter = instanceParam,
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
