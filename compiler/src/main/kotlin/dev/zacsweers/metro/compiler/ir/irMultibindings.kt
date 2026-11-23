// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.graph.computeMultibindingId
import dev.zacsweers.metro.compiler.graph.createMapBindingId
import dev.zacsweers.metro.compiler.graph.generatedMultibindingElementId
import dev.zacsweers.metro.compiler.graph.identity.AnnotationValueIdentity
import dev.zacsweers.metro.compiler.graph.identity.ConstantKind
import dev.zacsweers.metro.compiler.graph.multibindingElementId
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * [sourceFunction] restores the contributor's declaration when [annotations] came from a mirror.
 */
context(context: IrMetroContext)
internal fun IrTypeKey.transformIfIntoMultibinding(
  annotations: MetroAnnotations<IrAnnotation>,
  sourceFunction: IrSimpleFunction? = null,
): IrTypeKey {
  if (!annotations.isIntoMultibinding) {
    return this
  }

  val rawSymbol =
    annotations.symbol ?: reportCompilerBug("No symbol found for multibinding annotation")
  val declaration =
    rawSymbol.expectAsOrNull<IrSymbol>()?.owner?.expectAsOrNull<IrOverridableDeclaration<*>>()
      ?: reportCompilerBug(
        "Expected symbol to be an IrSymbol but was ${rawSymbol::class.simpleName}",
      )

  val function = sourceFunction ?: declaration as? IrSimpleFunction
  val source = function?.correspondingPropertySymbol?.owner ?: function ?: declaration
  val callableId = declaration.callableElementId()
  val generatedId = source.generatedElementId(annotations.mapKey)
  val elementId =
    when {
      generatedId != null -> generatedId
      source is IrProperty -> source.callableElementId(usePropertyName = true)
      source === declaration -> callableId
      else -> source.callableElementId()
    }

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

  val annotation =
    buildAnnotation(rawSymbol, context.metroSymbols.multibindingElement) {
      it.arguments[0] = irString(bindingId)
      it.arguments[1] = irString(elementId)
    }
  val qualifier = IrAnnotation(annotation)

  // Keep the old qualifier for ordering only when the source ID changed.
  val orderQualifier =
    if (elementId == callableId) {
      null
    } else {
      val identity = qualifier.identity
      val orderId = AnnotationValueIdentity.Constant(ConstantKind.String, callableId)
      identity.copy(arguments = listOf(identity.arguments.first(), orderId)).encoded
    }

  return copy(
    qualifier = qualifier,
    multibindingKeyData =
      IrTypeKey.MultibindingKeyData(
        this,
        annotations.mapKey,
        annotations.isElementsIntoSet,
        orderQualifier,
      ),
  )
}

/** Generated aliases and providers carry their origin and scope on the enclosing container. */
context(context: IrMetroContext)
private fun IrOverridableDeclaration<*>.generatedElementId(mapKey: IrAnnotation?): String? {
  val holder = parentClassOrNull ?: return null
  val marker = holder.getAnnotation(Symbols.FqNames.MetroContribution) ?: return null
  val scope = marker.scopeOrNull() ?: return null
  val origin = holder.originClassId() ?: return null

  return generatedMultibindingElementId(origin, scope, mapKey?.identity)
}

/** The original getter name is needed only for the compiler's collection order. */
private fun IrOverridableDeclaration<*>.callableElementId(
  usePropertyName: Boolean = false,
): String {
  val function =
    when (this) {
      is IrProperty -> getter
      is IrSimpleFunction -> this
    }

  return multibindingElementId(
    owner = parent.kotlinFqName,
    name = name,
    isSuspend = function?.isSuspend == true,
    parameterTypes = function?.parameters.orEmpty().map { it.type.typeIdentity() },
    returnType = function?.returnType?.typeIdentity(),
    callableName =
      if (usePropertyName) {
        name
      } else {
        function?.name ?: name
      },
    isProperty = usePropertyName,
  )
}

internal fun createMapBindingId(mapKey: IrType, elementTypeKey: IrTypeKey): String {
  return createMapBindingId(mapKey.typeIdentity(), elementTypeKey)
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
  val classRef =
    mapKey.classReferenceArgument(StandardNames.DEFAULT_VALUE_PARAMETER, fallbackIndex = 0)
      ?: return true // No value → use implicit
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

/**
 * Returns a new [MetroAnnotations] whose [MetroAnnotations.mapKey] has its implicit class key
 * populated with a reference to [implicitType]. Returns `this` if there is no map key or the map
 * key is not using the implicit class key sentinel.
 */
context(context: IrMetroContext)
internal fun MetroAnnotations<IrAnnotation>.withPopulatedImplicitClassKey(
  implicitType: IrType,
): MetroAnnotations<IrAnnotation> {
  val mapKey = this.mapKey ?: return this
  if (!isImplicitClassKeySentinel(mapKey.ir)) return this
  val copied = mapKey.ir.deepCopyWithSymbols()
  populateImplicitClassKey(copied, implicitType)
  return copy(mapKey = IrAnnotation(copied))
}

/**
 * Resolves the implicit class type for a binding with an implicit class key. For injected class
 * bindings, this is the class itself. For provided/binds bindings, this is the input parameter type
 * (which should already be populated during contribution code gen for contributions). Validation
 * and map generation must resolve the same class.
 */
internal fun resolveImplicitClassKeyType(binding: IrBinding): IrType {
  return when (binding) {
    is IrBinding.ConstructorInjected -> binding.type.defaultType
    is IrBinding.ObjectClass -> binding.type.defaultType
    is IrBinding.Alias -> binding.aliasedType.type.rawType().defaultType
    is IrBinding.Provided -> {
      // For @Binds, the implicit type is the single value parameter type.
      val function = binding.providerFactory.function
      val parameterType =
        function.extensionReceiverParameterCompat?.type
          ?: function.regularParameters.firstOrNull()?.type
      if (parameterType == null) {
        reportCompilerBug("Cannot resolve implicit class key type for Provided binding $binding")
      }
      parameterType.rawType().defaultType
    }
    else ->
      reportCompilerBug(
        "Implicit class keys require a class, binds, or provided binding. Found ${binding::class}.",
      )
  }
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
