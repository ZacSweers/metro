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
package dev.zacsweers.lattice.fir

import dev.zacsweers.lattice.appendIterableWith
import dev.zacsweers.lattice.unsafeLazy
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.types.ConstantValueKind

internal class LatticeFirAnnotation(val fir: FirAnnotationCall) {
  private val cachedHashKey by unsafeLazy { fir.computeAnnotationHash() }
  private val cachedToString by unsafeLazy {
    buildString {
      append('@')
      renderAsAnnotation(fir)
    }
  }

  // TODO
  //  fun isQualifier(session: FirSession) =
  // fir.resolvedType.toClassSymbol(session).isQualifierAnnotation
  //
  //  fun isScope(session: FirSession) = fir.resolvedType.toClassSymbol(session).isScopeAnnotation

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as LatticeFirAnnotation

    return cachedHashKey == other.cachedHashKey
  }

  override fun hashCode(): Int = cachedHashKey

  override fun toString() = cachedToString
}

private fun StringBuilder.renderAsAnnotation(firAnnotation: FirAnnotationCall) {
  val annotationClassName = firAnnotation.resolvedType.classId?.asString() ?: "<unbound>"
  append(annotationClassName)

  // TODO type args not supported

  if (firAnnotation.arguments.isEmpty()) return

  appendIterableWith(
    0 until firAnnotation.arguments.size,
    separator = ", ",
    prefix = "(",
    postfix = ")",
  ) { index ->
    renderAsAnnotationArgument(firAnnotation.arguments[index])
  }
}

private fun StringBuilder.renderAsAnnotationArgument(argument: FirExpression) {
  when (argument) {
    // TODO
    //      is IrConstructorCall -> renderAsAnnotation(irElement)
    is FirLiteralExpression -> {
      renderFirLiteralAsAnnotationArgument(argument)
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
