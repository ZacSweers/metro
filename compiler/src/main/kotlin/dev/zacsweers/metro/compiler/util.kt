// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.Locale
import kotlin.contracts.contract
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

// As of Kotlin 2.3, context parameters always have a mapped name of
// "$context-<simple name>"
internal const val CONTEXT_PARAMETER_NAME_PREFIX = $$"$context-"

internal fun generatedContextParameterName(classId: ClassId): Name {
  return "$CONTEXT_PARAMETER_NAME_PREFIX${classId.shortClassName.capitalizeUS()}".asName()
}

private val PLATFORM_TYPE_PACKAGES =
  setOf("android", "androidx", "java", "javax", "kotlin", "kotlinx", "scala")

internal fun ClassId.isPlatformType(): Boolean {
  return packageFqName.asString().let { packageName ->
    PLATFORM_TYPE_PACKAGES.any { platformPackage ->
      packageName == platformPackage || packageName.startsWith("$platformPackage.")
    }
  }
}

internal const val LOG_PREFIX = "[METRO]"

internal const val REPORT_METRO_MESSAGE =
  "This is possibly a bug in the Metro compiler, please report it with details and/or a reproducer to https://github.com/zacsweers/metro."

internal fun <T> memoize(initializer: () -> T) = lazy(LazyThreadSafetyMode.NONE, initializer)

internal inline fun <reified T : Any> Any.expectAs(): T {
  contract { returns() implies (this@expectAs is T) }
  return expectAsOrNull<T>()
    ?: reportCompilerBug("Expected $this to be of type ${T::class.qualifiedName}")
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

internal inline fun <T> T?.escapeIfNull(block: () -> Nothing): T {
  if (this == null) block()
  return this
}

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

// TODO this doesn't include the package name, should we include it
internal fun ClassId.scopeHintFunctionName(): Name = joinSimpleNames().shortClassName

internal fun reportCompilerBug(message: String): Nothing {
  error("${message.suffixIfNot(".")} $REPORT_METRO_MESSAGE ")
}

internal fun StringBuilder.appendLineWithUnderlinedContent(
  content: String,
  target: String = content,
  char: Char = '~',
) {
  appendLine(content)
  val lines = lines()
  val index = lines[lines.lastIndex - 1].lastIndexOf(target)
  if (index == -1) return
  repeat(index) { append(' ') }
  repeat(target.length) { append(char) }
}

/**
 * Copied from [kotlin.collections.joinTo] with the support for dynamically choosing a [separator].
 */
internal fun <T, A : Appendable> Iterable<T>.joinWithDynamicSeparatorTo(
  buffer: A,
  separator: (prev: T, next: T) -> CharSequence,
  prefix: CharSequence = "",
  postfix: CharSequence = "",
  limit: Int = -1,
  truncated: CharSequence = "...",
  transform: ((T) -> CharSequence)? = null,
): A {
  buffer.append(prefix)
  var count = 0
  var prev: T? = null
  for (element in this) {
    if (++count > 1) {
      buffer.append(separator(prev!!, element))
    }
    prev = element
    if (limit !in 0..<count) {
      buffer.appendElement(element, transform)
    } else break
  }
  if (limit in 0..<count) buffer.append(truncated)
  buffer.append(postfix)
  return buffer
}

private fun <T> Appendable.appendElement(element: T, transform: ((T) -> CharSequence)?) {
  when {
    transform != null -> append(transform(element))
    element is CharSequence? -> append(element)
    element is Char -> append(element)
    else -> append(element.toString())
  }
}

internal fun computeMetroDefault(
  behavior: OptionalBindingBehavior,
  isAnnotatedOptionalDep: () -> Boolean,
  hasDefaultValue: () -> Boolean,
): Boolean {
  return if (behavior == OptionalBindingBehavior.DISABLED) {
    false
  } else if (hasDefaultValue()) {
    if (behavior.requiresAnnotatedParameters) {
      isAnnotatedOptionalDep()
    } else {
      true
    }
  } else {
    false
  }
}

/**
 * [singleOrNull] but if there are multiple elements it will throw an error instead of returning
 * null
 */
internal fun <T> Sequence<T>.singleOrNullUnlessMultiple(
  onError: (T) -> Nothing,
  predicate: (T) -> Boolean = { true },
): T? {
  var found: T? = null
  for (element in this) {
    if (predicate(element)) {
      if (found != null) {
        onError(found)
      } else {
        found = element
      }
    }
  }
  return found
}

internal fun <K, V> MutableMap<K, MutableSet<V>>.getAndAdd(key: K, value: V): MutableSet<V> {
  return getOrInit(key).also { it.add(value) }
}

internal fun <K, V> MutableMap<K, MutableSet<V>>.getOrInit(key: K): MutableSet<V> {
  return getOrPut(key, ::mutableSetOf)
}
