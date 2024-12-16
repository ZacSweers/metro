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

import dev.zacsweers.lattice.appendIterableWith
import dev.zacsweers.lattice.transformers.LatticeTransformerContext
import dev.zacsweers.lattice.unsafeLazy
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.parentAsClass

internal class IrAnnotation(val ir: IrConstructorCall) : Comparable<IrAnnotation> {
  private val cachedHashKey by unsafeLazy { ir.computeAnnotationHash() }
  private val cachedToString by unsafeLazy {
    buildString {
      append('@')
      renderAsAnnotation(ir)
    }
  }

  fun LatticeTransformerContext.isQualifier() = ir.type.rawType().isQualifierAnnotation

  fun LatticeTransformerContext.isScope() = ir.type.rawType().isScopeAnnotation

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as IrAnnotation

    return cachedHashKey == other.cachedHashKey
  }

  override fun hashCode(): Int = cachedHashKey

  override fun toString() = cachedToString

  override fun compareTo(other: IrAnnotation): Int = cachedToString.compareTo(other.cachedToString)
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun StringBuilder.renderAsAnnotation(irAnnotation: IrConstructorCall) {
  val annotationClassName =
    irAnnotation.symbol.takeIf { it.isBound }?.owner?.parentAsClass?.name?.asString() ?: "<unbound>"
  append(annotationClassName)

  // TODO type args not supported

  if (irAnnotation.valueArgumentsCount == 0) return

  appendIterableWith(
    0 until irAnnotation.valueArgumentsCount,
    separator = ", ",
    prefix = "(",
    postfix = ")",
  ) { index ->
    renderAsAnnotationArgument(irAnnotation.getValueArgument(index))
  }
}

private fun StringBuilder.renderAsAnnotationArgument(irElement: IrElement?) {
  when (irElement) {
    null -> append("<null>")
    is IrConstructorCall -> renderAsAnnotation(irElement)
    is IrConst -> {
      renderIrConstAsAnnotationArgument(irElement)
    }
    is IrVararg -> {
      appendIterableWith(irElement.elements, prefix = "[", postfix = "]", separator = ", ") {
        renderAsAnnotationArgument(it)
      }
    }
    else -> append("...")
  }
}

private fun StringBuilder.renderIrConstAsAnnotationArgument(const: IrConst) {
  val quotes =
    when (const.kind) {
      IrConstKind.String -> "\""
      IrConstKind.Char -> "'"
      else -> ""
    }
  append(quotes)
  append(const.value.toString())
  append(quotes)
}
