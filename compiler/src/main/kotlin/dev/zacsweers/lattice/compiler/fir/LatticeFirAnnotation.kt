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
package dev.zacsweers.lattice.compiler.fir

import dev.zacsweers.lattice.compiler.appendIterableWith
import dev.zacsweers.lattice.compiler.md5base64
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.types.ConstantValueKind

internal class LatticeFirAnnotation(val fir: FirAnnotationCall) {
  private val cachedHashKey by unsafeLazy { fir.computeAnnotationHash() }
  private val cachedToString by unsafeLazy {
    buildString { renderAsAnnotation(fir, simple = false) }
  }

  fun simpleString() = buildString { renderAsAnnotation(fir, simple = true) }

  fun hashString(): String = md5base64(listOf(cachedToString))

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as LatticeFirAnnotation

    return cachedHashKey == other.cachedHashKey
  }

  override fun hashCode(): Int = cachedHashKey

  override fun toString() = cachedToString
}

private fun StringBuilder.renderAsAnnotation(firAnnotation: FirAnnotationCall, simple: Boolean) {
  val annotationClassName =
    if (simple) {
      firAnnotation.resolvedType.renderReadable()
    } else {
      firAnnotation.resolvedType.renderReadableWithFqNames()
    }
  append(annotationClassName)

  // TODO type args not supported

  if (firAnnotation.arguments.isEmpty()) return

  appendIterableWith(
    0 until firAnnotation.arguments.size,
    separator = ", ",
    prefix = "(",
    postfix = ")",
  ) { index ->
    renderAsAnnotationArgument(firAnnotation.arguments[index], simple)
  }
}

private fun StringBuilder.renderAsAnnotationArgument(argument: FirExpression, simple: Boolean) {
  when (argument) {
    is FirAnnotationCall -> renderAsAnnotation(argument, simple)
    is FirLiteralExpression -> {
      renderFirLiteralAsAnnotationArgument(argument)
    }
    is FirGetClassCall -> {
      val id =
        (argument.argument as? FirResolvedQualifier)?.symbol?.classId?.asSingleFqName() ?: "<Error>"
      append(id)
      append("::class")
    }
    // TODO
    //      is IrVararg -> {
    //        appendIterableWith(irElement.elements, prefix = "[", postfix = "]", separator = ", ")
    // {
    //          renderAsAnnotationArgument(it)
    //        }
    //      }
    else -> append("...")
  }
}

private fun StringBuilder.renderFirLiteralAsAnnotationArgument(const: FirLiteralExpression) {
  val quotes =
    when (const.kind) {
      ConstantValueKind.Char -> "'"
      ConstantValueKind.String -> "\""
      else -> ""
    }
  append(quotes)
  append(const.value.toString())
  append(quotes)
}
