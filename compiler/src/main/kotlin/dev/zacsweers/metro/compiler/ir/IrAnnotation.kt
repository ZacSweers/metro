// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.appendIterableWith
import dev.zacsweers.metro.compiler.graph.identity.AnnotationIdentity
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.platform.jvm.isJvm

/**
 * An annotation key with platform-specific class-literal identity and source IR for diagnostics.
 */
internal class IrAnnotation
private constructor(val ir: IrConstructorCall, context: IrMetroContext) : Comparable<IrAnnotation> {
  val identity: AnnotationIdentity by memoize { ir.annotationIdentity(context.platform.isJvm()) }
  private val cachedHashKey by memoize { identity.hashCode() }
  private val cachedToString by memoize { render(short = true) }

  override fun equals(other: Any?): Boolean {
    if (this === other) {
      return true
    }
    if (other !is IrAnnotation) {
      return false
    }

    return cachedHashKey == other.cachedHashKey && identity == other.identity
  }

  override fun hashCode(): Int = cachedHashKey

  override fun toString() = cachedToString

  override fun compareTo(other: IrAnnotation): Int {
    if (this == other) return 0
    return identity.encoded.compareTo(other.identity.encoded)
  }

  fun render(
    short: Boolean = true,
    useSiteTarget: String? = null,
    useRelativeClassNames: Boolean = false,
  ): String = ir.renderAnnotation(short, useSiteTarget, useRelativeClassNames)

  companion object {
    /** Array class literals retain component types and dimensions on the JVM. */
    context(context: IrMetroContext)
    operator fun invoke(ir: IrConstructorCall): IrAnnotation {
      return IrAnnotation(ir, context)
    }
  }
}

context(context: IrMetroContext)
internal fun IrConstructorCall.asIrAnnotation() = IrAnnotation(this)

/** Binds the compiler context for annotation factory references. */
internal fun IrMetroContext.createIrAnnotation(ir: IrConstructorCall): IrAnnotation =
  with(this) { IrAnnotation(ir) }

/** Renders annotations without constructing their platform-specific identity. */
internal fun IrConstructorCall.renderAnnotation(
  short: Boolean = true,
  useSiteTarget: String? = null,
  useRelativeClassNames: Boolean = false,
): String = buildString {
  append('@')
  useSiteTarget?.let {
    append(it)
    append(":")
  }
  renderAsAnnotation(this@renderAnnotation, short, useRelativeClassNames)
}

private fun StringBuilder.renderAsAnnotation(
  irAnnotation: IrConstructorCall,
  short: Boolean,
  useRelativeClassNames: Boolean,
) {
  val annotationClassName =
    irAnnotation.symbol
      .takeIf { it.isBound }
      ?.owner
      ?.parentAsClass
      ?.let {
        when {
          !short -> it.kotlinFqName.asString()
          useRelativeClassNames -> it.classId?.relativeClassName?.asString() ?: it.name.asString()
          else -> it.name.asString()
        }
      } ?: "<unbound>"
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
        typeArg.renderTo(this, short = short, useRelativeClassNames = useRelativeClassNames)
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
    renderAsAnnotationArgument(irAnnotation.arguments[index], short, useRelativeClassNames)
  }
}

private fun StringBuilder.renderAsAnnotationArgument(
  irElement: IrElement?,
  short: Boolean,
  useRelativeClassNames: Boolean,
) {
  when (irElement) {
    null -> append("<null>")
    is IrConstructorCall -> renderAsAnnotation(irElement, short, useRelativeClassNames)
    is IrConst -> renderIrConstAsAnnotationArgument(irElement)
    is IrVararg -> {
      appendIterableWith(irElement.elements, prefix = "[", postfix = "]", separator = ", ") {
        renderAsAnnotationArgument(it, short, useRelativeClassNames)
      }
    }
    is IrClassReference -> {
      irElement.classType.renderTo(
        this,
        short = short,
        useRelativeClassNames = useRelativeClassNames,
      )
      append("::class")
    }
    is IrGetEnumValue -> {
      val parent = irElement.symbol.owner.parentAsClass.classIdOrFail
      val enumClassName =
        when {
          !short -> parent.asSingleFqName().asString()
          useRelativeClassNames -> parent.relativeClassName.asString()
          else -> parent.shortClassName.asString()
        }
      append(enumClassName)
      append('.')
      append(irElement.symbol.owner.name.asString())
    }
    else ->
      reportCompilerBug(
        "Unrecognized annotation argument type: $irElement (type ${irElement::class.java})",
      )
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
