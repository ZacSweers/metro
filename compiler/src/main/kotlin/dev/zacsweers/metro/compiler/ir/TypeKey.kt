// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.render

// TODO cache these in DependencyGraphTransformer or shared transformer data
internal data class TypeKey(val type: IrType, val qualifier: IrAnnotation? = null) :
  Comparable<TypeKey> {
  override fun toString(): String = render(short = true)

  override fun compareTo(other: TypeKey) = toString().compareTo(other.toString())

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

  private fun IrType.renderShort(): String {
    val simpleName = simpleName
    val args =
      if (this is IrSimpleType) {
        arguments
          .takeUnless { it.isEmpty() }
          ?.joinToString(", ", prefix = "<", postfix = ">") {
            it.typeOrNull?.renderShort() ?: "<error>"
          }
          .orEmpty()
      } else {
        ""
      }
    return "$simpleName$args"
  }
}
