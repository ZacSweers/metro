// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.BaseTypeKey
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.defaultType

@Poko
internal class IrTypeKey
private constructor(
  override val type: IrType,
  override val qualifier: IrAnnotation?,
  // TODO these extra properties are awkward. Should we make this a sealed class?
  @Poko.Skip val multibindingKeyData: MultibindingKeyData? = null,
) : BaseTypeKey<IrType, IrAnnotation, IrTypeKey> {
  data class MultibindingKeyData(
    /**
     * For multibinding contributions, this is the original multibinding type key (Set<T> or Map<K,
     * V>) that this contribution belongs to. Used to implicitly create the multibinding when
     * processing parent graph contributions.
     */
    val multibindingTypeKey: IrTypeKey? = null,
    /** The original @MapKey annotation for multibinding map contributions. */
    val mapKey: IrAnnotation? = null,
    val isElementsIntoSet: Boolean = false,
  )

  private val cachedRender by memoize { render(short = false, includeQualifier = true) }

  val classId by memoize { type.rawTypeOrNull()?.classId }

  val hasTypeArgs: Boolean
    get() = type is IrSimpleType && type.arguments.isNotEmpty()

  /**
   * If this type key has a `@MultibindingElement` qualifier, returns the `bindingId` that
   * identifies which multibinding (Set or Map) this contribution belongs to.
   */
  val multibindingBindingId: String? by memoize {
    val qualifierIr = qualifier?.ir ?: return@memoize null
    if (qualifierIr.annotationClass.classId != Symbols.ClassIds.MultibindingElement) {
      return@memoize null
    }
    @Suppress("UNCHECKED_CAST") (qualifierIr.arguments[0] as? IrConst)?.value?.expectAs<String>()
  }

  /**
   * If this type key has a `@MultibindingElement` qualifier, returns the `elementId` that
   * disambiguates this element from other elements in the multibinding.
   */
  val multibindingBindingElementId: String? by memoize {
    val qualifierIr = qualifier?.ir ?: return@memoize null
    if (qualifierIr.annotationClass.classId != Symbols.ClassIds.MultibindingElement) {
      return@memoize null
    }
    @Suppress("UNCHECKED_CAST") (qualifierIr.arguments[1] as? IrConst)?.value?.expectAs<String>()
  }

  override fun copy(type: IrType, qualifier: IrAnnotation?): IrTypeKey {
    return IrTypeKey(type, qualifier, multibindingKeyData)
  }

  fun copy(
    type: IrType = this.type,
    qualifier: IrAnnotation? = this.qualifier,
    multibindingKeyData: MultibindingKeyData? = this.multibindingKeyData,
  ): IrTypeKey {
    return IrTypeKey(type, qualifier, multibindingKeyData)
  }

  override fun toString(): String = cachedRender

  override fun compareTo(other: IrTypeKey): Int {
    if (this == other) return 0
    return cachedRender.compareTo(other.cachedRender)
  }

  override fun render(short: Boolean, includeQualifier: Boolean): String = buildString {
    if (includeQualifier) {
      qualifier?.let {
        append(it.render(short))
        append(" ")
      }
    }
    type.renderTo(this, short)
  }

  companion object {
    context(context: IrMetroContext)
    operator fun invoke(clazz: IrClass): IrTypeKey {
      return invoke(clazz.defaultType, with(context) { clazz.qualifierAnnotation() })
    }

    operator fun invoke(
      type: IrType,
      qualifier: IrAnnotation? = null,
      multibindingKeyData: MultibindingKeyData? = null,
    ): IrTypeKey {
      // Canonicalize on the way through
      return IrTypeKey(
        type.canonicalize(patchMutableCollections = false, context = null),
        qualifier,
        multibindingKeyData,
      )
    }
  }
}

internal fun IrTypeKey.requireSetElementType(): IrType {
  return type.requireSimpleType().arguments[0].typeOrFail
}

internal fun IrTypeKey.requireMapKeyType(): IrType {
  return type.requireSimpleType().arguments[0].typeOrFail
}

internal fun IrTypeKey.requireMapValueType(): IrType {
  return type.requireSimpleType().arguments[1].typeOrFail
}

internal fun IrTypeKey.remapTypes(typeRemapper: TypeRemapper): IrTypeKey {
  if (type !is IrSimpleType) return this
  return IrTypeKey(typeRemapper.remapType(type), qualifier, multibindingKeyData)
}
