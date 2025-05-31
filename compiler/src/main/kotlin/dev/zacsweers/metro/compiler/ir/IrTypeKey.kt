// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.BaseTypeKey
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.render

// TODO cache these in DependencyGraphTransformer or shared transformer data
@Poko
internal class IrTypeKey(override val type: IrType, override val qualifier: IrAnnotation? = null) :
  BaseTypeKey<IrType, IrAnnotation, IrTypeKey> {

  private val cachedRender by unsafeLazy { render(short = false, includeQualifier = true) }

  override fun copy(type: IrType, qualifier: IrAnnotation?): IrTypeKey {
    return IrTypeKey(type, qualifier)
  }

  override fun toString(): String = cachedRender

  override fun compareTo(other: IrTypeKey): Int {
    if (this == other) return 0
    return COMPARATOR.compare(this, other)
  }

  override fun render(short: Boolean, includeQualifier: Boolean): String = buildString {
    if (includeQualifier) {
      qualifier?.let {
        append(it.render(short))
        append(" ")
      }
    }
    val typeString =
      if (short) {
        type.renderShort()
      } else {
        type.render()
      }
    append(typeString)
  }

  private fun IrType.renderShort(): String = buildString {
    append(simpleName)
    if (isMarkedNullable()) {
      append("?")
    }
    if (this@renderShort is IrSimpleType) {
      arguments
        .takeUnless { it.isEmpty() }
        ?.joinToString(", ", prefix = "<", postfix = ">") {
          it.typeOrNull?.renderShort() ?: "<error>"
        }
        ?.let { append(it) }
    }
  }

  private companion object {
    private val COMPARATOR = compareBy<IrTypeKey> { it.cachedRender }
      .thenBy { it.type.hashCode() }
      .thenBy { it.qualifier?.hashCode() ?: 0 }
  }
}

internal fun IrTypeKey.requireSetElementType(): IrType {
  return type.expectAs<IrSimpleType>().arguments[0].typeOrFail
}

internal fun IrTypeKey.requireMapKeyType(): IrType {
  return type.expectAs<IrSimpleType>().arguments[0].typeOrFail
}

internal fun IrTypeKey.requireMapValueType(): IrType {
  return type.expectAs<IrSimpleType>().arguments[1].typeOrFail
}
