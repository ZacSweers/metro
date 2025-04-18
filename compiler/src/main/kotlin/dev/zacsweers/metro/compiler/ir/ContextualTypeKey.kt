// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.WrappedType
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import org.jetbrains.kotlin.backend.jvm.JvmSymbols
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.backend.jvm.ir.isWithFlexibleNullability
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isClassWithFqName
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.render

/** A class that represents a type with contextual information. */
@Poko
internal class ContextualTypeKey(
  val typeKey: TypeKey,
  val wrappedType: WrappedType<IrType>,
  val hasDefault: Boolean = false,
  val isIntoMultibinding: Boolean = false,
  @Poko.Skip val rawType: IrType? = null,
) {
  val isDeferrable: Boolean = wrappedType.isDeferrable()
  val requiresProviderInstance: Boolean = isDeferrable

  val isWrappedInProvider: Boolean
    get() = wrappedType is WrappedType.Provider

  val isWrappedInLazy: Boolean
    get() = wrappedType is WrappedType.Lazy

  val isLazyWrappedInProvider: Boolean
    get() = wrappedType is WrappedType.Provider && wrappedType.innerType is WrappedType.Lazy

  override fun toString(): String = render(short = true)

  fun withTypeKey(typeKey: TypeKey, rawType: IrType? = null): ContextualTypeKey {
    return ContextualTypeKey(typeKey, wrappedType, hasDefault, isIntoMultibinding, rawType)
  }

  fun render(short: Boolean, includeQualifier: Boolean = true): String = buildString {
    append(
      wrappedType.render { type ->
        if (type == typeKey.type) {
          typeKey.render(short, includeQualifier)
        } else {
          if (short) {
            type.renderShort()
          } else {
            type.render()
          }
        }
      }
    )
    if (hasDefault) {
      append(" = ...")
    }
  }

  fun toIrType(metroContext: IrMetroContext): IrType {
    rawType?.let {
      // Already cached it, use it
      return it
    }

    return when (val wt = wrappedType) {
      is WrappedType.Canonical -> wt.type
      is WrappedType.Provider -> {
        val innerType =
          ContextualTypeKey(typeKey, wt.innerType, hasDefault, isIntoMultibinding)
            .toIrType(metroContext)
        innerType.wrapInProvider(metroContext.symbols.metroProvider)
      }
      is WrappedType.Lazy -> {
        val innerType =
          ContextualTypeKey(typeKey, wt.innerType, hasDefault, isIntoMultibinding)
            .toIrType(metroContext)
        innerType.wrapInProvider(metroContext.symbols.stdlibLazy)
      }
      is WrappedType.Map -> {
        // For Map types, we need to create a Map<K, V> type
        val keyType = wt.keyType
        val valueType =
          ContextualTypeKey(typeKey, wt.valueType, hasDefault, isIntoMultibinding)
            .toIrType(metroContext)

        // Create a Map type with the key type and the processed value type
        val mapClass = metroContext.pluginContext.irBuiltIns.mapClass
        return mapClass.typeWith(keyType, valueType)
      }
    }
  }

  // TODO cache these in DependencyGraphTransformer or shared transformer data
  companion object {
    fun from(
      context: IrMetroContext,
      function: IrSimpleFunction,
      annotations: MetroAnnotations<IrAnnotation>,
      type: IrType = function.returnType,
    ): ContextualTypeKey =
      type.asContextualTypeKey(
        context,
        with(context) {
          function.correspondingPropertySymbol?.owner?.qualifierAnnotation()
            ?: function.qualifierAnnotation()
        },
        false,
        annotations.isIntoMultibinding,
      )

    fun from(
      context: IrMetroContext,
      parameter: IrValueParameter,
      type: IrType = parameter.type,
    ): ContextualTypeKey =
      type.asContextualTypeKey(
        context = context,
        qualifierAnnotation = with(context) { parameter.qualifierAnnotation() },
        hasDefault = parameter.defaultValue != null,
        isIntoMultibinding = false,
      )

    fun create(
      typeKey: TypeKey,
      isWrappedInProvider: Boolean = false,
      isWrappedInLazy: Boolean = false,
      isLazyWrappedInProvider: Boolean = false,
      hasDefault: Boolean = false,
      isIntoMultibinding: Boolean = false,
      rawType: IrType? = null,
    ): ContextualTypeKey {
      val wrappedType =
        when {
          isLazyWrappedInProvider ->
            WrappedType.Provider(WrappedType.Lazy(WrappedType.Canonical(typeKey.type)))
          isWrappedInProvider -> WrappedType.Provider(WrappedType.Canonical(typeKey.type))
          isWrappedInLazy -> WrappedType.Lazy(WrappedType.Canonical(typeKey.type))
          else -> WrappedType.Canonical(typeKey.type)
        }

      return ContextualTypeKey(
        typeKey = typeKey,
        wrappedType = wrappedType,
        hasDefault = hasDefault,
        isIntoMultibinding = isIntoMultibinding,
        rawType = rawType,
      )
    }

    /** Left for backward compat */
    operator fun invoke(typeKey: TypeKey): ContextualTypeKey {
      return create(typeKey)
    }
  }
}

internal fun IrType.findProviderSupertype(context: IrMetroContext): IrType? {
  check(this is IrSimpleType) { "Unrecognized IrType '${javaClass}': ${render()}" }
  val rawTypeClass = rawTypeOrNull() ?: return null
  // Get the specific provider type it implements
  return rawTypeClass.getAllSuperTypes(context.pluginContext, excludeSelf = false).firstOrNull {
    it.rawTypeOrNull()?.classId in context.symbols.providerTypes
  }
}

internal fun IrType.implementsProviderType(context: IrMetroContext): Boolean {
  return findProviderSupertype(context) != null
}

internal fun IrType.asContextualTypeKey(
  context: IrMetroContext,
  qualifierAnnotation: IrAnnotation?,
  hasDefault: Boolean,
  isIntoMultibinding: Boolean,
): ContextualTypeKey {
  check(this is IrSimpleType) { "Unrecognized IrType '${javaClass}': ${render()}" }

  val declaredType = this

  // Analyze the type to determine its wrapped structure
  val wrappedType = declaredType.asWrappedType(context)

  val typeKey =
    TypeKey(
      when (wrappedType) {
        is WrappedType.Canonical -> wrappedType.type
        else -> wrappedType.canonicalType()
      },
      qualifierAnnotation,
    )

  return ContextualTypeKey(
    typeKey = typeKey,
    wrappedType = wrappedType,
    hasDefault = hasDefault,
    isIntoMultibinding = isIntoMultibinding,
    rawType = this,
  )
}

private fun IrSimpleType.asWrappedType(context: IrMetroContext): WrappedType<IrType> {
  val rawClassId = rawTypeOrNull()?.classId

  // Check if this is a Map type
  if (rawClassId == Symbols.ClassIds.map && arguments.size == 2) {
    val keyType = arguments[0].typeOrFail
    val valueType = arguments[1].typeOrFail

    // Recursively analyze the value type
    val valueWrappedType = valueType.expectAs<IrSimpleType>().asWrappedType(context)

    return WrappedType.Map(keyType, valueWrappedType) {
      context.pluginContext.irBuiltIns.mapClass.typeWith(keyType, valueWrappedType.canonicalType())
    }
  }

  // Check if this is a Provider type
  if (rawClassId in context.symbols.providerTypes) {
    val innerType = arguments[0].typeOrFail

    // Recursively analyze the inner type
    val innerWrappedType = innerType.expectAs<IrSimpleType>().asWrappedType(context)

    return WrappedType.Provider(innerWrappedType)
  }

  // Check if this is a Lazy type
  if (rawClassId in context.symbols.lazyTypes) {
    val innerType = arguments[0].typeOrFail

    // Recursively analyze the inner type
    val innerWrappedType = innerType.expectAs<IrSimpleType>().asWrappedType(context)

    return WrappedType.Lazy(innerWrappedType)
  }

  // If it's not a special type, it's a canonical type
  val adjustedType =
    if (isWithFlexibleNullability()) {
      // Java types may be "Flexible" nullable types, assume not null here
      makeNotNull().removeAnnotations {
        it.annotationClass.isClassWithFqName(JvmSymbols.FLEXIBLE_NULLABILITY_ANNOTATION_FQ_NAME)
      }
    } else {
      this
    }

  return WrappedType.Canonical(adjustedType)
}

private fun IrType.renderShort(): String = buildString {
  append(simpleName)
  if (isMarkedNullable()) {
    append("?")
  }
  if (this@renderShort is IrSimpleType) {
    arguments
      .takeUnless { it.isEmpty() }
      ?.joinToString(", ", prefix = "<", postfix = ">") { it.typeOrFail.renderShort() }
      ?.let { append(it) }
  }
}
