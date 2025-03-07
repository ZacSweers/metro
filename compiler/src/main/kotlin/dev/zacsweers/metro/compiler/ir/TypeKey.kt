// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.render

// TODO cache these in DependencyGraphTransformer or shared transformer data
@Poko
internal class TypeKey(val type: IrType, val qualifier: IrAnnotation? = null) :
  Comparable<TypeKey> {

  private val cachedRender by unsafeLazy { render(short = false, includeQualifier = true) }

  fun copy(type: IrType = this.type, qualifier: IrAnnotation? = this.qualifier): TypeKey {
    return TypeKey(type, qualifier)
  }

  override fun toString(): String = cachedRender

  override fun compareTo(other: TypeKey): Int {
    if (this == other) return 0
    return cachedRender.compareTo(other.cachedRender)
  }

  fun render(short: Boolean, includeQualifier: Boolean = true): String = buildString {
    if (includeQualifier) {
      qualifier?.let {
        append(it)
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
}
