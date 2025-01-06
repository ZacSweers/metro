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
package dev.zacsweers.lattice.compiler

import java.util.Locale
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import org.jetbrains.kotlin.name.Name

internal const val LOG_PREFIX = "[LATTICE]"

internal fun <T> unsafeLazy(initializer: () -> T) = lazy(LazyThreadSafetyMode.NONE, initializer)

@OptIn(ExperimentalContracts::class)
internal inline fun <reified T : Any> Any.expectAs(): T {
  contract { returns() implies (this@expectAs is T) }
  return expectAsOrNull<T>() ?: error("Expected $this to be of type ${T::class.qualifiedName}")
}

@OptIn(ExperimentalContracts::class)
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

internal fun <T, R> Iterable<T>.mapToSet(transform: (T) -> R): Set<R> {
  return mapTo(mutableSetOf(), transform)
}

internal fun <T, R : Any> Iterable<T>.mapNotNullToSet(transform: (T) -> R?): Set<R> {
  return mapNotNullTo(mutableSetOf(), transform)
}

internal inline fun <T, reified R> List<T>.mapToArray(transform: (T) -> R): Array<R> {
  return Array(size) { transform(get(it)) }
}

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

internal inline fun <T, C : Collection<T>, O> C.ifNotEmpty(body: C.() -> O?): O? =
  if (isNotEmpty()) this.body() else null

internal val String.withoutLineBreaks: String
  get() = lineSequence().joinToString(" ") { it.trim() }

internal infix operator fun Name.plus(other: String) = (asString() + other).asName()

internal infix operator fun Name.plus(other: Name) = (asString() + other.asString()).asName()
