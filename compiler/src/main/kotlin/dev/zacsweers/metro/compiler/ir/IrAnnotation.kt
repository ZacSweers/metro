// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.appendIterableWith
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrErrorExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.platform.jvm.isJvm

private val JAVA_VOID_CLASS_ID = ClassId.topLevel(FqName("java.lang.Void"))

/**
 * An annotation key with platform-specific class-literal identity and source IR for diagnostics.
 */
internal class IrAnnotation
private constructor(val ir: IrConstructorCall, context: IrMetroContext) : Comparable<IrAnnotation> {
  private val identity by memoize {
    ir.annotationIdentity(context.platform.isJvm())
  }
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

  override fun compareTo(other: IrAnnotation): Int = identity.compareTo(other.identity)

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

/**
 * Keeps argument boundaries and kinds in annotation identity. Hash collisions and punctuation
 * inside string values don't merge distinct qualifiers. Ordering uses the same structure.
 */
private data class AnnotationIdentity(
  val kind: String,
  val value: String = "",
  val elements: List<AnnotationIdentity> = emptyList(),
) : Comparable<AnnotationIdentity> {
  override fun compareTo(other: AnnotationIdentity): Int {
    val kindOrder = kind.compareTo(other.kind)
    if (kindOrder != 0) {
      return kindOrder
    }
    val valueOrder = value.compareTo(other.value)
    if (valueOrder != 0) {
      return valueOrder
    }
    for (index in 0 until minOf(elements.size, other.elements.size)) {
      val elementOrder = elements[index].compareTo(other.elements[index])
      if (elementOrder != 0) {
        return elementOrder
      }
    }
    return elements.size.compareTo(other.elements.size)
  }
}

private fun IrConstructorCall.annotationIdentity(isJvm: Boolean): AnnotationIdentity {
  val parameters = symbol.owner.parameters
  val values = arguments.mapIndexed { index, argument ->
    // Available defaults give explicit and omitted arguments the same identity.
    val value = argument ?: annotationDefaultValue(parameters[index])
    value.annotationArgumentIdentity(isJvm)
  }
  return AnnotationIdentity("annotation", annotationClass.classIdOrFail.asString(), values)
}

/** Reads constructor or property defaults when the compiler makes them available. */
private fun IrConstructorCall.annotationDefaultValue(parameter: IrValueParameter): IrExpression? {
  val parameterDefault = parameter.defaultValue?.expression
  if (parameterDefault != null && parameterDefault !is IrErrorExpression) {
    return parameterDefault
  }

  val property = annotationClass.properties.firstOrNull { it.name == parameter.name }
  val initializer = property?.backingField?.initializer?.expression
  val propertyDefault =
    if (initializer is IrGetValue) {
      val sourceParameter = initializer.symbol.owner as? IrValueParameter
      sourceParameter?.defaultValue?.expression
    } else {
      initializer
    }
  if (propertyDefault != null && propertyDefault !is IrErrorExpression) {
    return propertyDefault
  }

  // Unavailable Kotlin 2.3 KLIB defaults keep their omitted-value identity.
  return null
}

private fun IrElement?.annotationArgumentIdentity(isJvm: Boolean): AnnotationIdentity =
  when (this) {
    null -> AnnotationIdentity("absent")
    is IrConst -> AnnotationIdentity("constant:${kind}", value.toString())
    is IrConstructorCall -> annotationIdentity(isJvm)
    is IrVararg ->
      AnnotationIdentity(
        "array",
        elements = elements.map { it.annotationArgumentIdentity(isJvm) },
      )
    is IrClassReference -> classType.annotationClassIdentity(isJvm)
    is IrGetEnumValue ->
      AnnotationIdentity(
        "enum",
        symbol.owner.parentAsClass.classIdOrFail.asString(),
        listOf(AnnotationIdentity("entry", symbol.owner.name.asString())),
      )
    else ->
      reportCompilerBug("Unrecognized annotation argument type: $this (type ${this::class.java})")
  }

private fun IrType.annotationClassIdentity(isJvm: Boolean): AnnotationIdentity {
  val classId = classOrNull!!.owner.classIdOrFail
  if (isJvm && classId == JAVA_VOID_CLASS_ID) {
    // JVM annotation defaults can represent Nothing::class as java.lang.Void.
    return AnnotationIdentity("class", StandardClassIds.Nothing.asString())
  }
  if (isJvm && classId == StandardClassIds.Array) {
    // JVM array class literals retain their component types at runtime.
    val componentType = (this as IrSimpleType).arguments.singleOrNull()?.typeOrNull
    val componentIdentity =
      if (componentType != null) {
        componentType.annotationClassIdentity(isJvm)
      } else {
        AnnotationIdentity("class", StandardClassIds.Any.asString())
      }
    return AnnotationIdentity("class", classId.asString(), listOf(componentIdentity))
  }
  return AnnotationIdentity("class", classId.asString())
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
        "Unrecognized annotation argument type: $irElement (type ${irElement::class.java})"
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
