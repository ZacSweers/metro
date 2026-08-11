// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.appendIterableWith
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension.TypeResolveService
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.types.ConstantValueKind

internal class MetroFirAnnotation(
  val fir: FirAnnotation,
  private val session: FirSession,
  private val typeResolver: TypeResolveService? = null,
) {
  private val cachedHashKey by memoize { fir.computeAnnotationHash(session, typeResolver) }
  private val cachedToString by memoize {
    buildString { renderAsAnnotation(fir, session, typeResolver, simple = false) }
  }

  fun simpleString() = buildString { renderAsAnnotation(fir, session, typeResolver, simple = true) }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as MetroFirAnnotation

    // Fast fail with hash, authoritative check with rendered string
    if (cachedHashKey != other.cachedHashKey) return false
    return cachedToString == other.cachedToString
  }

  override fun hashCode(): Int = cachedHashKey

  override fun toString() = cachedToString
}

private fun StringBuilder.renderAsAnnotation(
  firAnnotation: FirAnnotation,
  session: FirSession,
  typeResolver: TypeResolveService?,
  simple: Boolean,
) {
  append('@')
  val annotationClassName =
    if (simple) {
      firAnnotation.resolvedType.renderReadable()
    } else {
      val annotationClassId = firAnnotation.toAnnotationClassIdSafe(session)
      if (annotationClassId == null) {
        firAnnotation.resolvedType.renderReadableWithFqNames()
      } else {
        annotationClassId.asSingleFqName().asString()
      }
    }
  append(annotationClassName)

  // TODO type args not supported

  if (firAnnotation is FirAnnotationCall) {
    if (firAnnotation.arguments.isEmpty()) return

    appendIterableWith(
      0 until firAnnotation.arguments.size,
      separator = ", ",
      prefix = "(",
      postfix = ")",
    ) { index ->
      renderAsAnnotationArgument(firAnnotation.arguments[index], session, typeResolver, simple)
    }
  } else {
    if (firAnnotation.argumentMapping.mapping.isEmpty()) return

    appendIterableWith(
      firAnnotation.argumentMapping.mapping.entries,
      separator = ", ",
      prefix = "(",
      postfix = ")",
    ) { (name, arg) ->
      append(name)
      append("=")
      renderAsAnnotationArgument(arg, session, typeResolver, simple)
    }
  }
}

private fun StringBuilder.renderAsAnnotationArgument(
  argument: FirExpression,
  session: FirSession,
  typeResolver: TypeResolveService?,
  simple: Boolean,
) {
  when (argument) {
    is FirAnnotationCall -> renderAsAnnotation(argument, session, typeResolver, simple)
    is FirNamedArgumentExpression ->
      renderAsAnnotationArgument(argument.expression, session, typeResolver, simple)
    is FirLiteralExpression -> {
      renderFirLiteralAsAnnotationArgument(argument)
    }
    is FirGetClassCall -> {
      val classId = argument.resolveClassIdForAnnotationValue(session, typeResolver)
      append(classId?.asSingleFqName() ?: "<Error>")
      append("::class")
    }
    is FirFunctionCall -> {
      val evaluated =
        with(session.compatContext) {
          argument.evaluateAsCompat(session, FirElement::class)
        }
      val evaluatedExpression = evaluated as? FirExpression
      if (evaluatedExpression == null || evaluatedExpression === argument) {
        append("...")
      } else {
        renderAsAnnotationArgument(evaluatedExpression, session, typeResolver, simple)
      }
    }
    is FirPropertyAccessExpression -> {
      // Enum entry or const val reference.
      // Use toResolvedCallableSymbol() (not toResolvedPropertySymbol()) because
      // enum entries are FirEnumEntrySymbol, not FirPropertySymbol.
      val symbol = argument.calleeReference.toResolvedCallableSymbol()
      append(symbol?.callableId ?: "...")
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
