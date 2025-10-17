// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.appendIterableWith
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass

internal class IrAnnotation(val ir: IrConstructorCall) : Comparable<IrAnnotation> {
  private val cachedHashKey by memoize { ir.computeAnnotationHash() }
  private val cachedToString by memoize { render(short = true) }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as IrAnnotation

    return cachedHashKey == other.cachedHashKey
  }

  override fun hashCode(): Int = cachedHashKey

  override fun toString() = cachedToString

  override fun compareTo(other: IrAnnotation): Int = cachedToString.compareTo(other.cachedToString)

  fun render(short: Boolean = true, useSiteTarget: String? = null): String {
    return buildString {
      append('@')
      useSiteTarget?.let {
        append(it)
        append(":")
      }
      renderAsAnnotation(ir, short)
    }
  }
}

internal fun IrConstructorCall.asIrAnnotation() = IrAnnotation(this)

private fun StringBuilder.renderAsAnnotation(irAnnotation: IrConstructorCall, short: Boolean) {
  val annotationClassName =
    irAnnotation.symbol
      .takeIf { it.isBound }
      ?.owner
      ?.parentAsClass
      ?.let { if (short) it.name.asString() else it.kotlinFqName.asString() } ?: "<unbound>"
  append(annotationClassName)

  if (irAnnotation.typeArguments.isNotEmpty()) {
    appendIterableWith(
      0 until irAnnotation.typeArguments.size,
      separator = ", ",
      prefix = "<",
      postfix = ">",
    ) { index ->
      val typeArg = irAnnotation.typeArguments[index]
      if (typeArg == null) {
        append("null")
      } else {
        typeArg.renderTo(this, short = short)
      }
    }
  }

  if (irAnnotation.arguments.isEmpty()) return

  appendIterableWith(
    0 until irAnnotation.arguments.size,
    separator = ", ",
    prefix = "(",
    postfix = ")",
  ) { index ->
    renderAsAnnotationArgument(irAnnotation.arguments[index], short)
  }
}

private fun StringBuilder.renderAsAnnotationArgument(irElement: IrElement?, short: Boolean) {
  when (irElement) {
    null -> append("<null>")
    is IrConstructorCall -> renderAsAnnotation(irElement, short)
    is IrConst -> renderIrConstAsAnnotationArgument(irElement)
    is IrVararg -> {
      appendIterableWith(irElement.elements, prefix = "[", postfix = "]", separator = ", ") {
        renderAsAnnotationArgument(it, short)
      }
    }
    is IrClassReference -> {
      irElement.classType.renderTo(this, short = short)
      append("::class")
    }
    is IrGetEnumValue -> {
      val parent = irElement.symbol.owner.parentAsClass.classIdOrFail
      if (short) {
        append(parent.shortClassName)
      } else {
        append(parent.asSingleFqName())
      }
      append('.')
      append(irElement.symbol.owner.name.asString())
    }
    else ->
      reportCompilerBug("Unrecognized annotation argument type: $irElement (type ${irElement::class.java})")
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
