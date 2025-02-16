// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.render

// TODO cache these in DependencyGraphTransformer or shared transformer data
internal class TypeKey(type: IrType, val qualifier: IrAnnotation? = null) : Comparable<TypeKey> {

  val type = type.removeAnnotations()

  private val cachedRender by unsafeLazy { render(short = false, includeQualifier = true) }

  fun copy(type: IrType = this.type, qualifier: IrAnnotation? = this.qualifier): TypeKey {
    return TypeKey(type, qualifier)
  }

  override fun toString(): String = render(short = true)

  override fun compareTo(other: TypeKey) = cachedRender.compareTo(other.cachedRender)

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

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as TypeKey

    return cachedRender == other.cachedRender
  }

  override fun hashCode(): Int {
    return cachedRender.hashCode()
  }
}
