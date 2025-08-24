// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.Locale
import kotlin.contracts.contract
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal const val LOG_PREFIX = "[METRO]"

internal const val REPORT_METRO_MESSAGE = "This is a bug in the Metro compiler, please report it to https://github.com/zacsweers/metro."

internal fun <T> unsafeLazy(initializer: () -> T) = lazy(LazyThreadSafetyMode.NONE, initializer)

internal inline fun <reified T : Any> Any.expectAs(): T {
  contract { returns() implies (this@expectAs is T) }
  return expectAsOrNull<T>() ?: reportCompilerBug("Expected $this to be of type ${T::class.qualifiedName}")
}

internal inline fun <reified T : Any> Any.expectAsOrNull(): T? {
  contract { returnsNotNull() implies (this@expectAsOrNull is T) }
  if (this !is T) return null
  return this
}

internal fun Name.capitalizeUS(): Name {
  val newName =
    asString().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
  return if (isSpecial) {
    Name.special(newName)
  } else {
    Name.identifier(newName)
  }
}

internal fun String.capitalizeUS() = replaceFirstChar {
  if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
}

internal fun String.decapitalizeUS() = replaceFirstChar { it.lowercase(Locale.US) }

internal fun <T> Iterable<T>.filterToSet(predicate: (T) -> Boolean): Set<T> {
  return filterTo(mutableSetOf(), predicate)
}

internal fun <T, R> Iterable<T>.mapToSet(transform: (T) -> R): Set<R> {
  return mapTo(mutableSetOf(), transform)
}

internal fun <T, R> Sequence<T>.mapToSet(transform: (T) -> R): Set<R> {
  return mapTo(mutableSetOf(), transform)
}

internal fun <T, R> Iterable<T>.flatMapToSet(transform: (T) -> Iterable<R>): Set<R> {
  return flatMapTo(mutableSetOf(), transform)
}

internal fun <T, R> Sequence<T>.flatMapToSet(transform: (T) -> Sequence<R>): Set<R> {
  return flatMapTo(mutableSetOf(), transform)
}

internal fun <T, R : Any> Iterable<T>.mapNotNullToSet(transform: (T) -> R?): Set<R> {
  return mapNotNullTo(mutableSetOf(), transform)
}

internal fun <T, R : Any> Sequence<T>.mapNotNullToSet(transform: (T) -> R?): Set<R> {
  return mapNotNullTo(mutableSetOf(), transform)
}

internal inline fun <T, reified R> List<T>.mapToArray(transform: (T) -> R): Array<R> {
  return Array(size) { transform(get(it)) }
}

internal inline fun <T, reified R> Array<T>.mapToArray(transform: (T) -> R): Array<R> {
  return Array(size) { transform(get(it)) }
}

internal inline fun <T, C : Collection<T>, O> C.ifNotEmpty(body: C.() -> O?): O? =
  if (isNotEmpty()) this.body() else null

internal fun <T, R> Iterable<T>.mapToSetWithDupes(transform: (T) -> R): Pair<Set<R>, Set<R>> {
  val dupes = mutableSetOf<R>()
  val destination = mutableSetOf<R>()
  for (item in this) {
    val transformed = transform(item)
    if (!destination.add(transformed)) {
      dupes += transformed
    }
  }
  return destination to dupes
}

internal inline fun <T, Buffer : Appendable> Buffer.appendIterableWith(
  iterable: Iterable<T>,
  prefix: String,
  postfix: String,
  separator: String,
  renderItem: Buffer.(T) -> Unit,
) {
  append(prefix)
  var isFirst = true
  for (item in iterable) {
    if (!isFirst) append(separator)
    renderItem(item)
    isFirst = false
  }
  append(postfix)
}

internal inline fun <T> T.letIf(condition: Boolean, block: (T) -> T): T {
  return if (condition) block(this) else this
}

// omit the `get-` prefix for property names starting with the *word* `is`, like `isProperty`,
// but not for names which just start with those letters, like `issues`.
internal val isWordPrefixRegex = "^is([^a-z].*)".toRegex()

internal fun String.asName(): Name = Name.identifier(this)

internal val String.withoutLineBreaks: String
  get() = lineSequence().joinToString(" ") { it.trim() }

internal infix operator fun Name.plus(other: String) = (asString() + other).asName()

internal infix operator fun Name.plus(other: Name) = (asString() + other.asString()).asName()

internal fun Boolean?.orElse(ifNull: Boolean): Boolean = this ?: ifNull

internal fun String.split(index: Int): Pair<String, String> {
  return substring(0, index) to substring(index + 1)
}

/**
 * Behaves essentially the same as `single()`, except if there is not a single element it will throw
 * the provided error message instead of the generic error message. This can be really helpful for
 * providing more targeted info about a use-case where normally mangled line numbers and a generic
 * error message would make debugging painful in a consuming project.
 */
internal fun <T> Collection<T>.singleOrError(errorMessage: Collection<T>.() -> String): T {
  if (size != 1) {
    reportCompilerBug(errorMessage())
  }
  return single()
}

internal fun CallableId.render(short: Boolean, isProperty: Boolean): String {
  // Render like so: dev.zacsweers.metro.sample.multimodule.parent.ParentGraph#provideNumberService
  return buildString {
    classId?.let {
      if (short) {
        append(it.shortClassName.asString())
      } else {
        append(it.asSingleFqName().asString())
      }
      append("#")
    }
    append(callableName.asString())
    if (!isProperty) {
      append("()")
    }
  }
}

internal fun <T : Comparable<T>> List<T>.compareTo(other: List<T>): Int {
  val sizeCompare = size.compareTo(other.size)
  if (sizeCompare != 0) return sizeCompare
  for (i in indices) {
    val cmp = this[i].compareTo(other[i])
    if (cmp != 0) return cmp
  }
  return 0
}

internal fun String.suffixIfNot(suffix: String) =
  if (this.endsWith(suffix)) this else "$this$suffix"

internal fun ClassId.scopeHintFunctionName(): Name = joinSimpleNames().shortClassName

internal fun reportCompilerBug(message: String): Nothing {
  error("${message.suffixIfNot(".")} $REPORT_METRO_MESSAGE ")
}
