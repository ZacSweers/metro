// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.getAnnotationStringValue
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.render

internal fun IrTypeKey.transformMultiboundQualifier(
  metroContext: IrMetroContext,
  annotations: MetroAnnotations<IrAnnotation>,
): IrTypeKey {
  if (!annotations.isIntoMultibinding) {
    return this
  }

  val rawSymbol = annotations.symbol ?: error("No symbol found for multibinding annotation")
  val symbol =
    rawSymbol.expectAsOrNull<IrSymbol>()
      ?: error("Expected symbol to be an IrSymbol but was ${rawSymbol::class.simpleName}")

  val elementId = symbol.multibindingElementId
  val bindingId =
    if (annotations.isIntoMap) {
      val mapKey = annotations.mapKeys.first()
      val mapKeyType = metroContext.mapKeyType(mapKey)
      createMapBindingId(mapKeyType, this)
    } else if (annotations.isElementsIntoSet) {
      val elementType = type.expectAs<IrSimpleType>().arguments.first().typeOrFail
      val elementTypeKey = copy(type = elementType)
      elementTypeKey.multibindingId
    } else {
      multibindingId
    }

  val newQualifier =
    metroContext.pluginContext.buildAnnotation(symbol, metroContext.symbols.multibindingElement) {
      it.putValueArgument(0, irString(bindingId))
      it.putValueArgument(1, irString(elementId))
    }

  return copy(qualifier = IrAnnotation(newQualifier))
}

/** Returns a unique ID for this specific binding */
internal val IrSymbol.multibindingElementId: String
  get() {
    // Signature is only present if public, so we can't rely on it here.
    return hashCode().toString()
  }

/**
 * The ID of the binding this goes into. This is the qualifier + type render.
 *
 * For Set multibindings, this is the element typekey.
 *
 * For Map multibindings, they make a composite ID with [createMapBindingId].
 *
 * Examples:
 * - `okhttp3.Interceptor`
 * - `@NetworkInterceptor okhttp3.Interceptor`
 */
internal val IrTypeKey.multibindingId: String
  get() = render(short = false, includeQualifier = true)

internal fun createMapBindingId(mapKey: IrType, elementTypeKey: IrTypeKey): String {
  return "${mapKey.render()}_${elementTypeKey.multibindingId}"
}

internal fun IrMetroContext.shouldUnwrapMapKeyValues(mapKey: IrAnnotation): Boolean {
  return shouldUnwrapMapKeyValues(mapKey.ir)
}

internal fun IrMetroContext.shouldUnwrapMapKeyValues(mapKey: IrConstructorCall): Boolean {
  val mapKeyMapKeyAnnotation = mapKey.annotationClass.mapKeyAnnotation()!!.ir
  // TODO FIR check valid MapKey
  //  - single arg
  //  - no generics
  val unwrapValue = mapKeyMapKeyAnnotation.getSingleConstBooleanArgumentOrNull() != false
  return unwrapValue
}

// TODO this is probably not robust enough
internal fun IrMetroContext.mapKeyType(mapKey: IrAnnotation): IrType {
  val unwrapValues = metroContext.shouldUnwrapMapKeyValues(mapKey)
  return if (unwrapValues) {
      mapKey.ir.annotationClass.primaryConstructor!!.valueParameters[0].type
    } else {
      mapKey.ir.type
    }
    .removeAnnotations()
}
