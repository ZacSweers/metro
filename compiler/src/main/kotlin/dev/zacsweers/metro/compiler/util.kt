// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.ir.IrDependencyGraph
import java.util.Locale
import kotlin.contracts.InvocationKind
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

/**
 * Thread-safety mode used by [memoize]. Default is [LazyThreadSafetyMode.PUBLICATION] so callers
 * remain safe under the parallel transformation pool wired up in [IrDependencyGraph]. The plugin
 * registrar swaps this to [LazyThreadSafetyMode.NONE] when `parallelThreads == 0`, which removes
 * the per-access CAS/volatile cost for the 100+ memoized properties in the hot compile path.
 *
 * Treated as process-global mutable state, matching the existing single-compilation-per-process
 * assumption (see [dev.zacsweers.metro.compiler.ir.cache.IrThreadUnsafeCachesFactory]).
 */
@Volatile
internal var memoizeThreadSafetyMode: LazyThreadSafetyMode = LazyThreadSafetyMode.PUBLICATION

internal fun <T> memoize(initializer: () -> T) = lazy(memoizeThreadSafetyMode, initializer)

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
  @Suppress("RETURN_VALUE_NOT_USED")
  contract {
    callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    //    condition holdsIn block
  }
  return if (condition) block(this) else this
}

internal inline fun <T> T.runIf(condition: Boolean, block: T.() -> T): T {
  @Suppress("RETURN_VALUE_NOT_USED")
  contract {
    callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    //    condition holdsIn block
  }
  return if (condition) block(this) else this
}

internal inline fun <T> T.alsoIf(condition: Boolean, block: (T) -> Unit): T {
  @Suppress("RETURN_VALUE_NOT_USED")
  contract {
    callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    // Declares that the condition is assumed to be true inside the lambda
    //    condition holdsIn block
  }
  if (condition) block(this)
  return this
}

internal inline fun <T> T.applyIf(condition: Boolean, block: T.() -> Unit): T {
  @Suppress("RETURN_VALUE_NOT_USED")
  contract {
    callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    // Declares that the condition is assumed to be true inside the lambda
    //    condition holdsIn block
  }
  if (condition) block(this)
  return this
}

internal inline fun <T> T?.escapeIfNull(block: () -> Nothing): T {
  contract {
    callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    returns() implies (this@escapeIfNull != null)
  }
  if (this == null) block()
  return this
}

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

internal fun Name.suffixIfNot(suffix: String) =
  if (asString().endsWith(suffix)) this else "$this$suffix".asName()

internal fun ClassId.scopeHintFunctionName(): Name = MetroHints.hintFunctionName(this)

internal inline fun metroCheck(condition: Boolean, body: () -> String) {
  if (!condition) {
    reportCompilerBug(body())
  }
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

internal fun StringBuilder.appendLineWithUnderlinedRanges(
  content: String,
  ranges: List<IntRange>,
  char: Char = '~',
) {
  appendLine(content)
  val underline = CharArray(content.length) { ' ' }
  for (range in ranges) {
    val start = range.first.coerceAtLeast(0)
    val end = range.last.coerceAtMost(content.lastIndex)
    if (start > end) continue
    for (index in start..end) {
      underline[index] = char
    }
  }
  if (underline.none { it == char }) return
  append(underline.concatToString().trimEnd())
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

internal val ClassId.safePathString: String
  get() = asFqNameString().replace('.', '_')
