// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.symbols.Symbols
import okio.utf8Size
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// Soft cap for [safeNestedSimpleName]: 255 minus headroom for `$FactoryImpl`, a chained
// `$Impl_xxxxx`, `.class`, and a small buffer.
internal const val NESTED_CLASS_BINARY_NAME_LIMIT = 220

/** Includes the binary-name separator before Kotlin's generated companion object. */
internal const val GENERATED_COMPANION_NAME_BYTES = 10

/** The JVM class-file basename, without its package path or file extension. */
internal fun ClassId.binaryClassName(): String =
  relativeClassName.pathSegments().joinToString("$") { it.asString() }

/** Both FIR and IR must compute exactly the same provider-factory class ID. */
internal fun providerFactoryClassId(
  parentClassId: ClassId,
  callableName: Name,
  maxBytes: Int,
): ClassId {
  val suffix = Symbols.StringNames.METRO_FACTORY
  val preferredName = callableName.asString().capitalizeUS() + suffix
  return parentClassId
    .createNestedClassId(Name.identifier(preferredName))
    .truncate(
      maxLength = maxBytes,
      reservedNestedBytes = GENERATED_COMPANION_NAME_BYTES,
      hashSource = "provider-factory:${parentClassId.asString()}#${callableName.asString()}",
      requiredSuffix = suffix,
    )
}

/** Takes complete Unicode code points without allocating an encoded copy for every prefix. */
private fun String.takeUtf8Prefix(maxBytes: Int): String {
  var end = 0
  var bytes = 0
  while (end < length) {
    val codePoint = Character.codePointAt(this, end)
    val nextEnd = end + Character.charCount(codePoint)
    // Okio also accounts for the replacement of an unpaired surrogate.
    val nextBytes = utf8Size(end, nextEnd).toInt()
    if (nextBytes > maxBytes - bytes) break
    bytes += nextBytes
    end = nextEnd
  }
  return substring(0, end)
}

/**
 * Joins the simple names of a class with the given [separator] and [suffix].
 *
 * ```
 * val normalName = ClassName("com.example", "Outer", "Middle", "Inner")
 * val joinedName = normalName.joinSimpleNames(separator = "_", suffix = "Factory")
 *
 * println(joinedName) // com.example.Outer_Middle_InnerFactory
 * ```
 *
 * @throws IllegalArgumentException if the resulting class name is too long to be a valid file name.
 */
public fun ClassId.joinSimpleNames(
  separator: String = "_",
  suffix: String = "",
  camelCase: Boolean = false,
): ClassId =
  joinSimpleNamesPrivate(separator = separator, suffix = suffix, camelCase = camelCase)
    .checkFileLength()

private fun ClassId.joinSimpleNamesPrivate(
  separator: String = "_",
  suffix: String = "",
  camelCase: Boolean = false,
): ClassId =
  ClassId(
    packageFqName,
    Name.identifier(
      relativeClassName.pathSegments().joinToString(separator = separator, postfix = suffix) {
        if (camelCase) {
          it.asString().capitalizeUS()
        } else {
          it.asString()
        }
      }
    ),
  )

private fun ClassId.checkFileLength(): ClassId = apply {
  val len = relativeClassName.pathSegments().sumOf { it.identifier.length + 1 }.minus(1)
  require(len <= DEFAULT_MAX_GENERATED_CLASS_NAME_LENGTH) {
    "Class name is too long: $len  --  ${asString()}"
  }
}

/**
 * Joins the simple names of a class with the given [separator] and [suffix].
 *
 * The end of the name will be the separator followed by a hash of the [hashParams], so that
 * generated class names are unique. If the resulting class name is too long to be a valid file
 * name, it will be truncated by removing the last characters *before* the hash, but the hash be
 * unchanged.
 *
 * ```
 * val someScope = ClassName("com.example", "SomeScope")
 * val boundType = ClassName("com.example", "BoundType")
 * val normalName = ClassName("com.example", "Outer", "Middle", "Inner")
 * val joinedName = normalName.joinSimpleNamesAndTruncate(
 *   hashParams = listOf(someScope, boundType),
 *   separator = "_",
 *   suffix = "Factory"
 * )
 * println(joinedName) // com.example.Outer_Middle_InnerFactory_0a1b2c3d
 * ```
 *
 * @throws IllegalArgumentException if the resulting class name is too long to be a valid file name
 *   even after truncating.
 * @see ClassId.joinSimpleNames for a version that doesn't truncate the class name.
 */
public fun ClassId.joinSimpleNamesAndTruncate(
  separator: String = "_",
  suffix: String = "",
  innerClassLength: Int = 0,
  camelCase: Boolean = false,
): ClassId =
  joinSimpleNamesPrivate(separator = separator, suffix = suffix, camelCase = camelCase)
    .truncate(separator = separator, innerClassLength = innerClassLength)

/**
 * Truncates the class name to a valid file name length by removing characters from the end of the
 * class name. The [hashSuffix] of this will be appended to the class name with the given
 * [separator]. If the class name is too long, it will be truncated by removing the last characters
 * *before* the hash, but the hash will be unchanged.
 *
 * ```
 * val someScope = ClassName("com.example", "SomeScope")
 * val boundType = ClassName("com.example", "BoundType")
 * val normalName = ClassName("com.example", "Outer", "Middle", "Inner")
 * val truncatedName = normalName.truncate(
 *   hashParams = listOf(someScope, boundType),
 *   separator = "_",
 *   innerClassLength = 0
 * )
 * println(truncatedName) // com.example.Outer_Middle_Inner_0a1b2c3d
 * ```
 *
 * @throws IllegalArgumentException if the resulting class name is too long to be a valid file name
 *   even after truncating.
 */
public fun ClassId.truncate(separator: String = "_", innerClassLength: Int = 0): ClassId {
  val maxLength =
    DEFAULT_MAX_GENERATED_CLASS_NAME_LENGTH
      // a nested type that will be appended to this canonical name
      // with a '$' separator, like `$ParentComponent`
      .minus(innerClassLength + 1)
      // The class file name contains all parent class names as well, separated by '$',
      // so the lengths of those names must be subtracted from the max length.
      .minus(relativeClassName.pathSegments().dropLast(1).sumOf { it.asString().length + 1 })

  val className =
    relativeClassName
      .asString()
      // The hash is appended after truncating so that it's always present.
      .truncateName(
        maxBytes = maxLength,
        separator = separator,
        hashSource = this,
        forceHash = true,
      )

  return ClassId(packageFqName, Name.identifier(className)).checkFileLength()
}

/** Truncates a generated class against its complete UTF-8 binary name and future nested names. */
internal fun ClassId.truncate(
  maxLength: Int,
  reservedNestedBytes: Int = 0,
  hashSource: Any = this,
  requiredSuffix: String = "",
  forceHash: Boolean = false,
): ClassId {
  val parent = outerClassId
  val parentBytes =
    if (parent == null) {
      0
    } else {
      parent.binaryClassName().utf8Size().toInt() + 1
    }
  val availableBytes = maxLength - parentBytes - reservedNestedBytes
  val originalName = shortClassName.asString()
  val truncatedName =
    originalName.truncateName(
      maxBytes = availableBytes,
      hashSource = hashSource,
      requiredSuffix = requiredSuffix,
      forceHash = forceHash,
    )
  if (truncatedName == originalName) return this

  val name = Name.identifier(truncatedName)
  return parent?.createNestedClassId(name) ?: ClassId(packageFqName, name)
}

private fun String.truncateName(
  maxBytes: Int,
  hashSource: Any,
  separator: String = "_",
  requiredSuffix: String = "",
  forceHash: Boolean = false,
): String {
  require(endsWith(requiredSuffix)) { "Generated name '$this' must end with '$requiredSuffix'" }
  if (!forceHash && utf8Size() <= maxBytes) return this

  // Keep the existing compact deterministic hash so the descriptive name receives the remaining
  // space, including when its enclosing class was written by the user.
  // Reserve the hash suffix with its separator before taking the descriptive prefix.
  val suffix = "$separator${hashSource.hashSuffix}$requiredSuffix"
  val prefixBudget = (maxBytes - suffix.utf8Size().toInt()).coerceAtLeast(0)
  return removeSuffix(requiredSuffix).takeUtf8Prefix(prefixBudget) + suffix
}

public fun ClassId.generatedClass(suffix: String): ClassId {
  return joinSimpleNamesPrivate(separator = "_", suffix = suffix)
}

/**
 * Returns [candidate] unchanged if a class with that simple name nested inside this [ClassId] would
 * still produce a class file basename within the filesystem per-segment limit. Otherwise returns a
 * short, stable fallback `Impl_${hashSource.hashSuffix}`.
 *
 * The basename of a nested class's `.class` file joins all relative class name segments with `$`,
 * so chained nested generation (e.g. deep `@GraphExtension` impls and their factory impls) can
 * exceed the 255-byte per-segment limit on most filesystems. The fallback name is deterministic
 * across compilations via [hashSource]. See https://github.com/ZacSweers/metro/issues/2268.
 */
internal fun ClassId.safeNestedSimpleName(candidate: String, hashSource: Any): String {
  // `.`-separated and `$`-separated names have the same length.
  val parentBinaryLength = relativeClassName.asString().length
  val projected = parentBinaryLength + 1 + candidate.length
  return if (projected <= NESTED_CLASS_BINARY_NAME_LIMIT) {
    candidate
  } else {
    "Impl_${hashSource.hashSuffix}"
  }
}

public fun Collection<ClassId>.asFqNames(): Collection<FqName> = map { it.asSingleFqName() }
