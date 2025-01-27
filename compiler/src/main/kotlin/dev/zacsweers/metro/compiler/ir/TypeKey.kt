/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
