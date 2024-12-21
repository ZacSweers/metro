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
package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.ir.IrAnnotation
import dev.zacsweers.lattice.unsafeLazy
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.render

// TODO cache these in DependencyGraphTransformer or shared transformer data
internal data class TypeKey(val type: IrType, val qualifier: IrAnnotation? = null) :
  Comparable<TypeKey> {
  private val cachedToString by unsafeLazy {
    buildString {
      qualifier?.let {
        append(it)
        append(" ")
      }
      append(type.render())
    }
  }

  override fun toString(): String = cachedToString

  override fun compareTo(other: TypeKey) = toString().compareTo(other.toString())
}
