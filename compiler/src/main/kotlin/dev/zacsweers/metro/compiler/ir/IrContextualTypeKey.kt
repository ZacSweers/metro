// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.graph.BaseContextualTypeKey
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.TypeRemapper
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

  context(context: IrMetroContext)
  fun toIrType(): IrType {
    rawType?.let {
      // Already cached it, use it
      return it
    }

    return when (val wt = wrappedType) {
      is WrappedType.Canonical -> wt.type
      is WrappedType.Provider -> {
        val innerType = IrContextualTypeKey(typeKey, wt.innerType, hasDefault).toIrType()
        innerType.wrapInProvider(context.referenceClass(wt.providerType)!!)
      }

      is WrappedType.Lazy -> {
        val innerType = IrContextualTypeKey(typeKey, wt.innerType, hasDefault).toIrType()
        innerType.wrapInProvider(context.referenceClass(wt.lazyType)!!)
      }

      is WrappedType.Map -> {
        // For Map types, we need to create a Map<K, V> type
        val keyType = wt.keyType
        val valueType = IrContextualTypeKey(typeKey, wt.valueType, hasDefault).toIrType()

        // Create a Map type with the key type and the processed value type
        val mapClass = context.irBuiltIns.mapClass
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
      patchMutableCollections: Boolean = false,
      hasDefaultOverride: Boolean = false,
    ): IrContextualTypeKey {
      val typeToConvert =
        if (wrapInProvider) {
          type.wrapInProvider(context.metroSymbols.metroProvider)
        } else {
          type
        }
      return typeToConvert.asContextualTypeKey(
        with(context) {
          function.correspondingPropertySymbol?.owner?.qualifierAnnotation()
            ?: function.qualifierAnnotation()
        },
        hasDefaultOverride,
        patchMutableCollections,
        declaration = function,
      )
    }

    context(context: IrMetroContext)
    fun from(parameter: IrValueParameter, type: IrType = parameter.type): IrContextualTypeKey {
      return type.asContextualTypeKey(
        qualifierAnnotation = with(context) { parameter.qualifierAnnotation() },
        hasDefault = parameter.hasMetroDefault(),
        patchMutableCollections = false,
        declaration = parameter,
      )
    }

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
              rawType!!.requireSimpleType().arguments.single().typeOrFail.rawType().classIdOrFail
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
    operator fun invoke(typeKey: IrTypeKey, hasDefault: Boolean = false): IrContextualTypeKey {
      return create(typeKey, hasDefault = hasDefault)
    }
  }
}

context(context: IrMetroContext)
internal fun IrContextualTypeKey.stripLazy(): IrContextualTypeKey {
  return if (wrappedType !is WrappedType.Lazy) {
    this
  } else {
    IrContextualTypeKey(
      typeKey,
      wrappedType.innerType,
      hasDefault,
      rawType?.requireSimpleType()?.arguments?.single()?.typeOrFail,
    )
  }
}

context(context: IrMetroContext)
internal fun IrContextualTypeKey.wrapInProvider(
  providerType: IrClass = context.metroSymbols.metroProvider.owner
): IrContextualTypeKey {
  return if (wrappedType is WrappedType.Provider) {
    if (wrappedType.providerType == providerType) {
      this
    } else {
      IrContextualTypeKey(
        typeKey,
        WrappedType.Provider(wrappedType.innerType, providerType.classIdOrFail),
        hasDefault,
        rawType?.let {
          // New type with the original type's arguments
          providerType.symbol.typeWithArguments(it.requireSimpleType().arguments)
        },
      )
    }
  } else {
    IrContextualTypeKey(
      typeKey,
      WrappedType.Provider(wrappedType, providerType.classIdOrFail),
      hasDefault,
      rawType?.let { providerType.typeWith(it) },
    )
  }
}

context(context: IrMetroContext)
internal fun IrType.implementsProviderType(): Boolean {
  val rawType = rawTypeOrNull() ?: return false
  val allProviderClassIds =
    context.metroSymbols.providerTypes + Symbols.ClassIds.commonMetroProviders
  return rawType.implementsAny(allProviderClassIds)
}

context(context: IrMetroContext)
internal fun IrType.implementsLazyType(): Boolean {
  check(this is IrSimpleType) { "Unrecognized IrType '${javaClass}': ${render()}" }
  val rawTypeClass = rawTypeOrNull() ?: return false
  return rawTypeClass.classId in context.metroSymbols.lazyTypes
}

context(context: IrMetroContext)
internal fun IrType.asContextualTypeKey(
  qualifierAnnotation: IrAnnotation?,
  hasDefault: Boolean,
  patchMutableCollections: Boolean,
  declaration: IrDeclaration?,
): IrContextualTypeKey {
  val declaredType = requireSimpleType(declaration)

  // Analyze the type to determine its wrapped structure
  val wrappedType = declaredType.asWrappedType(patchMutableCollections, declaration)

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

context(context: IrMetroContext)
private fun IrSimpleType.asWrappedType(
  patchMutableCollections: Boolean,
  declaration: IrDeclaration?,
): WrappedType<IrType> {
  val rawClassId = rawTypeOrNull()?.classId

  // Check if this is a Map type
  if (rawClassId == StandardClassIds.Map && arguments.size == 2) {
    val keyType = arguments[0]
    val valueType = arguments[1]

    // Recursively analyze the value type
    val valueWrappedType =
      valueType.typeOrFail.requireSimpleType().asWrappedType(patchMutableCollections, declaration)

    return WrappedType.Map(keyType.typeOrFail, valueWrappedType) {
      context.irBuiltIns.mapClass.typeWithArguments(
        listOf(keyType, valueWrappedType.canonicalType())
      )
    }
  }

  // Check if this is a Provider type
  if (rawClassId in context.metroSymbols.providerTypes) {
    val innerType = arguments[0].typeOrFail

    // Recursively analyze the inner type
    val innerWrappedType =
      innerType.requireSimpleType(declaration).asWrappedType(patchMutableCollections, declaration)

    return WrappedType.Provider(innerWrappedType, rawClassId!!)
  }

  // Check if this is a Lazy type
  if (rawClassId in context.metroSymbols.lazyTypes) {
    val innerType = arguments[0].typeOrFail

    // Recursively analyze the inner type
    val innerWrappedType =
      innerType.requireSimpleType(declaration).asWrappedType(patchMutableCollections, declaration)

    return WrappedType.Lazy(innerWrappedType, rawClassId!!)
  }

  // If it's not a special type, it's a canonical type
  return WrappedType.Canonical(canonicalize(patchMutableCollections, context))
}

context(context: IrMetroContext)
internal fun WrappedType<IrType>.toIrType(): IrType {
  return when (this) {
    is WrappedType.Canonical -> type
    is WrappedType.Provider -> {
      val innerIrType = innerType.toIrType()
      val providerType = context.referenceClass(providerType)!!
      providerType.typeWith(innerIrType)
    }

    is WrappedType.Lazy -> {
      val innerIrType = innerType.toIrType()
      val lazyType = context.referenceClass(lazyType)!!
      lazyType.typeWith(innerIrType)
    }

    is WrappedType.Map -> {
      val keyIrType = keyType
      val valueIrType = valueType.toIrType()
      context.irBuiltIns.mapClass.typeWith(keyIrType, valueIrType)
    }
  }
}

internal fun WrappedType<IrType>.remapType(remapper: TypeRemapper): WrappedType<IrType> {
  return when (this) {
    is WrappedType.Canonical -> WrappedType.Canonical(remapper.remapType(type))
    is WrappedType.Provider -> {
      WrappedType.Provider(innerType.remapType(remapper), providerType)
    }

    is WrappedType.Lazy -> {
      WrappedType.Lazy(innerType.remapType(remapper), lazyType)
    }

    is WrappedType.Map -> {
      WrappedType.Map(remapper.remapType(keyType), valueType.remapType(remapper)) {
        remapper.remapType(type())
      }
    }
  }
}

internal fun IrContextualTypeKey.remapType(remapper: TypeRemapper): IrContextualTypeKey {
  return IrContextualTypeKey(
    typeKey.remapTypes(remapper),
    wrappedType.remapType(remapper),
    hasDefault,
    rawType?.let { remapper.remapType(it) },
  )
}
