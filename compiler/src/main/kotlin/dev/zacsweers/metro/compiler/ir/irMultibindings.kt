// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.reportCompilerBug
import java.util.Objects
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.render

context(context: IrMetroContext)
internal fun IrTypeKey.transformMultiboundQualifier(
  annotations: MetroAnnotations<IrAnnotation>
): IrTypeKey {
  if (!annotations.isIntoMultibinding) {
    return this
  }

  val rawSymbol = annotations.symbol ?: reportCompilerBug("No symbol found for multibinding annotation")
  val declaration =
    rawSymbol.expectAsOrNull<IrSymbol>()?.owner?.expectAsOrNull<IrOverridableDeclaration<*>>()
      ?: reportCompilerBug("Expected symbol to be an IrSymbol but was ${rawSymbol::class.simpleName}")

  val elementId = declaration.multibindingElementId
  val bindingId =
    if (annotations.isIntoMap) {
      val mapKey = annotations.mapKeys.first()
      val mapKeyType = mapKeyType(mapKey)
      createMapBindingId(mapKeyType, this)
    } else if (annotations.isElementsIntoSet) {
      val elementType = type.requireSimpleType().arguments.first().typeOrFail
      val elementTypeKey = copy(type = elementType)
      elementTypeKey.multibindingId
    } else {
      multibindingId
    }

  val newQualifier =
    buildAnnotation(rawSymbol, context.metroSymbols.multibindingElement) {
      it.arguments[0] = irString(bindingId)
      it.arguments[1] = irString(elementId)
    }

  return copy(qualifier = IrAnnotation(newQualifier))
}

/** Returns a unique ID for this specific binding */
internal val IrOverridableDeclaration<*>.multibindingElementId: String
  get() {
    var isSuspend = false
    val params =
      when (this) {
        is IrProperty -> {
          getter?.parameters?.map { it.type.rawType().kotlinFqName }.orEmpty()
        }
        is IrSimpleFunction -> {
          isSuspend = this.isSuspend
          this.parameters.map { it.type.rawType().kotlinFqName }
        }
      }
    // Signature is only present if public, so we can't rely on it here.
    return Objects.hash(parent.kotlinFqName, name, isSuspend, params).toString()
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

context(context: IrMetroContext)
internal fun shouldUnwrapMapKeyValues(mapKey: IrAnnotation): Boolean {
  return shouldUnwrapMapKeyValues(mapKey.ir)
}

context(context: IrMetroContext)
internal fun shouldUnwrapMapKeyValues(mapKey: IrConstructorCall): Boolean {
  val mapKeyMapKeyAnnotation = mapKey.annotationClass.explicitMapKeyAnnotation()!!.ir
  val unwrapValue = mapKeyMapKeyAnnotation.getSingleConstBooleanArgumentOrNull() != false
  return unwrapValue
}

context(context: IrMetroContext)
internal fun mapKeyType(mapKey: IrAnnotation): IrType {
  val unwrapValues = shouldUnwrapMapKeyValues(mapKey)
  return if (unwrapValues) {
      mapKey.ir.annotationClass.primaryConstructor!!.regularParameters[0].type
    } else {
      mapKey.ir.type
    }
    .removeAnnotations()
}
