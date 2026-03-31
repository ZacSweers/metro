// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.Objects
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.StandardClassIds

context(context: IrMetroContext)
internal fun IrTypeKey.transformIfIntoMultibinding(
  annotations: MetroAnnotations<IrAnnotation>
): IrTypeKey {
  if (!annotations.isIntoMultibinding) {
    return this
  }

  val rawSymbol =
    annotations.symbol ?: reportCompilerBug("No symbol found for multibinding annotation")
  val declaration =
    rawSymbol.expectAsOrNull<IrSymbol>()?.owner?.expectAsOrNull<IrOverridableDeclaration<*>>()
      ?: reportCompilerBug(
        "Expected symbol to be an IrSymbol but was ${rawSymbol::class.simpleName}"
      )

  val elementId = declaration.multibindingElementId

  // Compute bindingId and multibindingTypeKey based on the annotation type
  val bindingId =
    if (annotations.mapKey != null) {
      val mapKey = annotations.mapKey
      val mapKeyTypeIr = mapKeyType(mapKey)
      createMapBindingId(mapKeyTypeIr, this)
    } else {
      val elementTypeKey =
        if (annotations.isElementsIntoSet) {
          val elementType = type.requireSimpleType().arguments.first().typeOrFail
          copy(type = elementType)
        } else {
          this
        }
      elementTypeKey.computeMultibindingId()
    }

  val newQualifier =
    buildAnnotation(rawSymbol, context.metroSymbols.multibindingElement) {
      it.arguments[0] = irString(bindingId)
      it.arguments[1] = irString(elementId)
    }

  return copy(
    qualifier = IrAnnotation(newQualifier),
    multibindingKeyData =
      IrTypeKey.MultibindingKeyData(this, annotations.mapKey, annotations.isElementsIntoSet),
  )
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
internal fun IrTypeKey.computeMultibindingId(): String =
  render(short = false, includeQualifier = true)

internal fun createMapBindingId(mapKey: IrType, elementTypeKey: IrTypeKey): String {
  return "${mapKey.render(short = false)}_${elementTypeKey.computeMultibindingId()}"
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

/**
 * Checks if the given map key annotation's `@MapKey` meta-annotation has `implicitClassKey = true`.
 */
context(context: IrMetroContext)
internal fun hasImplicitClassKey(mapKey: IrConstructorCall): Boolean {
  val mapKeyMapKeyAnnotation = mapKey.annotationClass.explicitMapKeyAnnotation()?.ir ?: return false
  return mapKeyMapKeyAnnotation.getConstBooleanArgumentOrNull(Symbols.Names.implicitClassKey) ==
    true
}

/**
 * Checks if the given map key annotation's value is the `Nothing::class` sentinel, indicating it
 * should use the implicit class key.
 */
context(context: IrMetroContext)
internal fun isImplicitClassKeySentinel(mapKey: IrConstructorCall): Boolean {
  if (!hasImplicitClassKey(mapKey)) return false
  val valueArg = mapKey.arguments[0] ?: return true // No value → use implicit
  val classRef = valueArg as? IrClassReference ?: return false
  return classRef.classType.classOrNull?.owner?.classId == StandardClassIds.Nothing
}

/**
 * Populates an implicit class key annotation's value argument with a class reference to the given
 * [implicitType]. This replaces the `Nothing::class` sentinel with the actual class reference.
 */
context(context: IrMetroContext)
internal fun populateImplicitClassKey(mapKey: IrConstructorCall, implicitType: IrType) {
  val kClassType = context.irBuiltIns.kClassClass.typeWith(implicitType)
  mapKey.arguments[0] =
    IrClassReferenceImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = kClassType,
      symbol = implicitType.classOrNull ?: return,
      classType = implicitType,
    )
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
    .normalizeToKClassIfJavaClass()
}
