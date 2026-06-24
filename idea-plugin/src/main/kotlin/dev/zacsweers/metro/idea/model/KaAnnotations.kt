// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/** A structured, session-free snapshot of a resolved annotation argument value. */
internal sealed interface MetroKaAnnotationValue {
  data class Literal(val value: Any?) : MetroKaAnnotationValue

  data class EnumEntry(val callableId: CallableId?) : MetroKaAnnotationValue

  data class KClassRef(val classId: ClassId?) : MetroKaAnnotationValue

  data class Array(val values: List<MetroKaAnnotationValue>) : MetroKaAnnotationValue

  data class Nested(val annotation: MetroKaAnnotation) : MetroKaAnnotationValue

  data object Unsupported : MetroKaAnnotationValue
}

/**
 * An annotation participating in key/scope identity (a qualifier like `@Named("cdn")` or a scope
 * like `@SingleIn(AppScope::class)`). The Analysis API analog of the compiler's
 * `MetroFirAnnotation`/`IrAnnotation`, with the same canonical-render equality semantics — but
 * built from the structured resolved argument values rather than source text, so spelling
 * differences (named vs positional args, import styles) don't break identity.
 */
internal data class MetroKaAnnotation(
  val classId: ClassId,
  val arguments: List<Pair<Name, MetroKaAnnotationValue>>,
) {
  fun render(short: Boolean, useRelativeClassNames: Boolean = false): String = buildString {
    append('@')
    append(
      when {
        short -> classId.shortClassName.asString()
        useRelativeClassNames -> classId.relativeClassName.asString()
        else -> classId.asFqNameString()
      }
    )
    if (arguments.isNotEmpty()) {
      arguments.joinTo(this, separator = ", ", prefix = "(", postfix = ")") { (name, value) ->
        "${name.asString()} = ${renderValue(value, short, useRelativeClassNames)}"
      }
    }
  }

  private fun renderValue(
    value: MetroKaAnnotationValue,
    short: Boolean,
    useRelativeClassNames: Boolean,
  ): String {
    return when (value) {
      is MetroKaAnnotationValue.Literal ->
        when (val literal = value.value) {
          is String -> "\"$literal\""
          is Char -> "'$literal'"
          else -> literal.toString()
        }
      is MetroKaAnnotationValue.EnumEntry ->
        value.callableId?.let {
          if (short) it.callableName.asString() else it.asSingleFqName().asString()
        } ?: "<error>"
      is MetroKaAnnotationValue.KClassRef ->
        value.classId?.let {
          val name =
            when {
              short -> it.shortClassName.asString()
              useRelativeClassNames -> it.relativeClassName.asString()
              else -> it.asFqNameString()
            }
          "$name::class"
        } ?: "<error>"
      is MetroKaAnnotationValue.Array ->
        value.values.joinToString(separator = ", ", prefix = "[", postfix = "]") {
          renderValue(it, short, useRelativeClassNames)
        }
      is MetroKaAnnotationValue.Nested -> value.annotation.render(short, useRelativeClassNames)
      is MetroKaAnnotationValue.Unsupported -> "..."
    }
  }
}
