// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.graph.BaseContextualTypeKey
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.graph.WrappedType.Canonical
import dev.zacsweers.metro.compiler.graph.WrappedType.Provider
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.StandardClassIds

/** A class that represents a type with contextual information. */
internal class IrContextualTypeKey(
  override val typeKey: IrTypeKey,
  override val wrappedType: WrappedType<IrType>,
  override val hasDefault: Boolean = false,
  override val rawType: IrType? = null,
) : BaseContextualTypeKey<IrType, IrTypeKey, IrContextualTypeKey> {

  private val cachedRender by memoize { render(short = false) }
  private val cachedHashCode by memoize {
    var result = typeKey.hashCode()
    result = 31 * result + wrappedType.hashCode()
    result
  }

  context(metroContext: IrMetroContext)
  fun withIrTypeKey(typeKey: IrTypeKey, rawType: IrType? = null): IrContextualTypeKey {
    return IrContextualTypeKey(
      typeKey,
      wrappedType.withCanonicalType(typeKey.type),
      hasDefault,
      rawType ?: wrappedType.toIrType(),
    )
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
      is Canonical -> wt.type
      is Provider -> {
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
        mapClass.typeWith(keyType, valueType)
      }
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as IrContextualTypeKey

    if (typeKey != other.typeKey) return false
    if (wrappedType != other.wrappedType) return false

    return true
  }

  override fun hashCode(): Int = cachedHashCode

  override fun toString(): String = cachedRender

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
      val baseType = baseWrappedType(typeKey.type)
      val wrappedType =
        when {
          isLazyWrappedInProvider -> {
            val lazyType =
              rawType!!.requireSimpleType().arguments.single().typeOrFail.rawType().classIdOrFail
            Provider(WrappedType.Lazy(baseType, lazyType), rawClassId!!)
          }

          isWrappedInProvider -> {
            Provider(baseType, rawClassId!!)
          }

          isWrappedInLazy -> {
            WrappedType.Lazy(baseType, rawClassId!!)
          }

          else -> {
            baseType
          }
        }

      return IrContextualTypeKey(
        typeKey = typeKey,
        wrappedType = wrappedType,
        hasDefault = hasDefault,
        rawType = rawType,
      )
    }

    /**
     * Creates the appropriate base [WrappedType] for the given type. For Map types, creates
     * [WrappedType.Map] to ensure consistent representation with parameter analysis in
     * [asContextualTypeKey]/[asWrappedType].
     */
    private fun baseWrappedType(type: IrType): WrappedType<IrType> {
      if (type is IrSimpleType && type.arguments.size == 2) {
        if (type.classOrNull?.owner?.classId == StandardClassIds.Map) {
          return WrappedType.Map(
            type.arguments[0].typeOrFail,
            Canonical(type.arguments[1].typeOrFail),
          ) {
            type
          }
        }
      }
      return Canonical(type)
    }

    /** Left for backward compat */
    operator fun invoke(typeKey: IrTypeKey, hasDefault: Boolean = false): IrContextualTypeKey {
      return create(typeKey, hasDefault = hasDefault)
    }
  }
}

context(context: IrMetroContext)
internal fun IrContextualTypeKey.stripIfLazy(): IrContextualTypeKey {
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
internal fun IrContextualTypeKey.stripProvider(): IrContextualTypeKey {
  return if (wrappedType !is Provider) {
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

/**
 * Strips outer Provider/Lazy wrapping while preserving inner structure like map value types. This
 * is used for property lookup where the outer wrapping determines access type (scalar vs provider)
 * but the inner structure (e.g., Map<K, V> vs Map<K, Provider<V>>) determines the binding variant.
 */
context(context: IrMetroContext)
internal fun IrContextualTypeKey.stripOuterProviderOrLazy(): IrContextualTypeKey {
  return when (wrappedType) {
    is Provider -> stripProvider()
    is WrappedType.Lazy -> stripIfLazy()
    else -> this
  }
}

context(context: IrMetroContext)
internal fun IrContextualTypeKey.wrapInProvider(
  providerType: IrClass = context.metroSymbols.metroProvider.owner
): IrContextualTypeKey {
  return if (wrappedType is Provider) {
    if (wrappedType.providerType == providerType) {
      this
    } else {
      IrContextualTypeKey(
        typeKey,
        Provider(wrappedType.innerType, providerType.classIdOrFail),
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
      Provider(wrappedType, providerType.classIdOrFail),
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
        is Canonical -> wrappedType.type
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

    // Normalize map key type for canonical representation (Class -> KClass)
    val canonicalKeyType = keyType.typeOrFail.normalizeToKClassIfJavaClass()

    return WrappedType.Map(canonicalKeyType, valueWrappedType) {
      context.irBuiltIns.mapClass.typeWithArguments(
        listOf(canonicalKeyType, valueWrappedType.canonicalType())
      )
    }
  }

  // Check if this is a Provider type
  if (rawClassId in context.metroSymbols.providerTypes) {
    val innerType = arguments[0].typeOrFail

    // Recursively analyze the inner type
    val innerWrappedType =
      innerType.requireSimpleType(declaration).asWrappedType(patchMutableCollections, declaration)

    return Provider(innerWrappedType, rawClassId!!)
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
  return Canonical(canonicalize(patchMutableCollections, context))
}

context(context: IrMetroContext)
internal fun WrappedType<IrType>.toIrType(): IrType {
  return when (this) {
    is Canonical -> type
    is Provider -> {
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
    is Canonical -> Canonical(remapper.remapType(type))
    is Provider -> {
      Provider(innerType.remapType(remapper), providerType)
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

internal fun WrappedType<IrType>.withCanonicalType(type: IrType): WrappedType<IrType> {
  return when (this) {
    is Canonical -> Canonical(type)
    is Provider -> Provider(innerType.withCanonicalType(type), providerType)
    is WrappedType.Lazy -> WrappedType.Lazy(innerType.withCanonicalType(type), lazyType)
    is WrappedType.Map -> {
      val simpleType = type.requireSimpleType()
      WrappedType.Map(
        keyType = simpleType.arguments[0].typeOrFail,
        valueType = valueType.withCanonicalType(simpleType.arguments[1].typeOrFail),
        type = { type },
      )
    }
  }
}
