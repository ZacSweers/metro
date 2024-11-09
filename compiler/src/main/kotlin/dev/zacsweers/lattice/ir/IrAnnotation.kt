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
package dev.zacsweers.lattice.ir

import dev.zacsweers.lattice.transformers.LatticeTransformerContext
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.render

internal class IrAnnotation(val ir: IrConstructorCall) {
  val hashKey by lazy { ir.computeAnnotationHash() }

  fun LatticeTransformerContext.isQualifier() = ir.type.rawType().isQualifierAnnotation

  fun LatticeTransformerContext.isScope() = ir.type.rawType().isScopeAnnotation

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as IrAnnotation

    return hashKey == other.hashKey
  }

  override fun hashCode(): Int = hashKey

  override fun toString() = ir.render()
}
