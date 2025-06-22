// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.BaseContextualTypeKey
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.StandardClassIds

/** A class that represents a type with contextual information. */
@Poko
internal class IrContextualTypeKey(
  override val typeKey: IrTypeKey,
  override val wrappedType: WrappedType<IrType>,
  override val hasDefault: Boolean = false,
  @Poko.Skip override val rawType: IrType? = null,
) : BaseContextualTypeKey<IrType, IrTypeKey, IrContextualTypeKey> {
  override fun toString(): String = render(short = false)

  override fun withTypeKey(typeKey: IrTypeKey, rawType: IrType?): IrContextualTypeKey {
    return IrContextualTypeKey(typeKey, wrappedType, hasDefault, rawType)
  }

  override fun render(short: Boolean, includeQualifier: Boolean): String = buildString {
    append(
      wrappedType.render { type ->
        if (type == typeKey.type) {
          typeKey.render(short, includeQualifier)
        } else {
          type.render(short)
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
          IrContextualTypeKey(typeKey, wt.innerType, hasDefault).toIrType(metroContext)
        innerType.wrapInProvider(metroContext.pluginContext.referenceClass(wt.providerType)!!)
      }
      is WrappedType.Lazy -> {
        val innerType =
          IrContextualTypeKey(typeKey, wt.innerType, hasDefault).toIrType(metroContext)
        innerType.wrapInProvider(metroContext.pluginContext.referenceClass(wt.lazyType)!!)
      }
      is WrappedType.Map -> {
        // For Map types, we need to create a Map<K, V> type
        val keyType = wt.keyType
        val valueType =
          IrContextualTypeKey(typeKey, wt.valueType, hasDefault).toIrType(metroContext)

        // Create a Map type with the key type and the processed value type
        val mapClass = metroContext.pluginContext.irBuiltIns.mapClass
        return mapClass.typeWith(keyType, valueType)
      }
    }
  }

  // TODO cache these in DependencyGraphTransformer or shared transformer data
  companion object {
    context(context: IrMetroContext)
    fun from(
      function: IrSimpleFunction,
      type: IrType = function.returnType,
      wrapInProvider: Boolean = false,
    ): IrContextualTypeKey {
      return from(context, function, type, wrapInProvider)
    }

    fun from(
      context: IrMetroContext,
      function: IrSimpleFunction,
      type: IrType = function.returnType,
      wrapInProvider: Boolean = false,
    ): IrContextualTypeKey {
      val typeToConvert =
        if (wrapInProvider) {
          type.wrapInProvider(context.symbols.metroProvider)
        } else {
          type
        }
      return typeToConvert.asContextualTypeKey(
        context,
        with(context) {
          function.correspondingPropertySymbol?.owner?.qualifierAnnotation()
            ?: function.qualifierAnnotation()
        },
        false,
      )
    }

    fun from(
      context: IrMetroContext,
      parameter: IrValueParameter,
      type: IrType = parameter.type,
    ): IrContextualTypeKey =
      type.asContextualTypeKey(
        context = context,
        qualifierAnnotation = with(context) { parameter.qualifierAnnotation() },
        hasDefault = parameter.defaultValue != null,
      )

    fun create(
      typeKey: IrTypeKey,
      isWrappedInProvider: Boolean = false,
      isWrappedInLazy: Boolean = false,
      isLazyWrappedInProvider: Boolean = false,
      hasDefault: Boolean = false,
      rawType: IrType? = null,
    ): IrContextualTypeKey {
      val rawClassId = rawType?.rawTypeOrNull()?.classId
      val wrappedType =
        when {
          isLazyWrappedInProvider -> {
            val lazyType =
              rawType!!
                .expectAs<IrSimpleType>()
                .arguments
                .single()
                .typeOrFail
                .rawType()
                .classIdOrFail
            WrappedType.Provider(
              WrappedType.Lazy(WrappedType.Canonical(typeKey.type), lazyType),
              rawClassId!!,
            )
          }
          isWrappedInProvider -> {
            WrappedType.Provider(WrappedType.Canonical(typeKey.type), rawClassId!!)
          }
          isWrappedInLazy -> {
            WrappedType.Lazy(WrappedType.Canonical(typeKey.type), rawClassId!!)
          }
          else -> {
            WrappedType.Canonical(typeKey.type)
          }
        }

      return IrContextualTypeKey(
        typeKey = typeKey,
        wrappedType = wrappedType,
        hasDefault = hasDefault,
        rawType = rawType,
      )
    }

    /** Left for backward compat */
    operator fun invoke(typeKey: IrTypeKey): IrContextualTypeKey {
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
): IrContextualTypeKey {
  check(this is IrSimpleType) { "Unrecognized IrType '${javaClass}': ${render()}" }

  val declaredType = this

  // Analyze the type to determine its wrapped structure
  val wrappedType = declaredType.asWrappedType(context)

  val typeKey =
    IrTypeKey(
      when (wrappedType) {
        is WrappedType.Canonical -> wrappedType.type
        else -> wrappedType.canonicalType()
      },
      qualifierAnnotation,
    )

  // TODO do we need to transform contextkey for multibindings here?

  return IrContextualTypeKey(
    typeKey = typeKey,
    wrappedType = wrappedType,
    hasDefault = hasDefault,
    rawType = this,
  )
}

private fun IrSimpleType.asWrappedType(context: IrMetroContext): WrappedType<IrType> {
  val rawClassId = rawTypeOrNull()?.classId

  // Check if this is a Map type
  if (rawClassId == StandardClassIds.Map && arguments.size == 2) {
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

    return WrappedType.Provider(innerWrappedType, rawClassId!!)
  }

  // Check if this is a Lazy type
  if (rawClassId in context.symbols.lazyTypes) {
    val innerType = arguments[0].typeOrFail

    // Recursively analyze the inner type
    val innerWrappedType = innerType.expectAs<IrSimpleType>().asWrappedType(context)

    return WrappedType.Lazy(innerWrappedType, rawClassId!!)
  }

  // If it's not a special type, it's a canonical type
  return WrappedType.Canonical(canonicalize())
}
