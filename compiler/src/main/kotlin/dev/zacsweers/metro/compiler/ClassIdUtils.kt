// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.security.MessageDigest
import java.util.Base64
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private const val HASH_STRING_LENGTH = 8
private const val MAX_FILE_NAME_LENGTH =
  255
    .minus(14) // ".kapt_metadata" is the longest extension
    .minus(8) // "Provider" is the longest suffix that Dagger might add

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
  require(len <= MAX_FILE_NAME_LENGTH) { "Class name is too long: $len  --  ${asString()}" }
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
  hashParams: List<Any> = emptyList(),
  separator: String = "_",
  suffix: String = "",
  innerClassLength: Int = 0,
  camelCase: Boolean = false,
): ClassId =
  joinSimpleNamesPrivate(separator = separator, suffix = suffix, camelCase = camelCase)
    .truncate(
      hashParams = listOf(this) + hashParams,
      separator = separator,
      innerClassLength = innerClassLength,
    )

/**
 * Truncates the class name to a valid file name length by removing characters from the end of the
 * class name. The hash of the [hashParams] will be appended to the class name with the given
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
public fun ClassId.truncate(
  hashParams: List<Any>,
  separator: String = "_",
  innerClassLength: Int = 0,
): ClassId {

  val maxLength =
    MAX_FILE_NAME_LENGTH
      // hash suffix with separator: `_a0b2c3d4`
      .minus(HASH_STRING_LENGTH + separator.length)
      // a nested type that will be appended to this canonical name
      // with a '$' separator, like `$ParentComponent`
      .minus(innerClassLength + 1)
      // The class file name contains all parent class names as well, separated by '$',
      // so the lengths of those names must be subtracted from the max length.
      .minus(relativeClassName.pathSegments().dropLast(1).sumOf { it.asString().length + 1 })

  val hash = md5base64(hashParams)

  val className =
    relativeClassName
      .asString()
      .take(maxLength)
      // The hash is appended after truncating so that it's always present.
      .plus("$separator$hash")

  return ClassId(packageFqName, Name.identifier(className)).checkFileLength()
}

internal fun md5base64(params: List<Any>): String {
  val md5 =
    MessageDigest.getInstance("MD5")
      .apply { params.forEach { update(it.toString().toByteArray()) } }
      .digest()
      .copyOfRange(0, 5)

  // base64 URL encoder without padding uses exactly the characters allowed in both JVM bytecode and
  // Dalvik bytecode names
  return Base64.getUrlEncoder().withoutPadding().encodeToString(md5)
}

/**
 * Generates a unique hint file name by adding the package name as the first simple name, then
 * joining all simple names with the [separator] and [suffix].
 *
 * @see ClassId.joinSimpleNames for the joining logic
 */
public fun ClassId.generateHintFileName(
  separator: String = "",
  suffix: String = "",
  capitalizePackage: Boolean = true,
): String =
  ClassId(
      packageFqName,
      FqName.fromSegments(
        listOfNotNull(
          packageFqName
            .asString()
            .takeIf { it.isNotEmpty() }
            ?.replace('.', '_')
            ?.let { if (capitalizePackage) it.capitalizeUS() else it },
          *relativeClassName.pathSegments().map { it.asString() }.toTypedArray(),
        )
      ),
      isLocal = false,
    )
    .joinSimpleNamesPrivate(separator = separator, suffix = suffix)
    .truncate(hashParams = listOf(asFqNameString()), innerClassLength = 0)
    .relativeClassName
    .asString()

/** For merge-annotated classes, returns the expected [ClassId] of the generated merged class. */
// TODO eventually also need to account for scope
public fun ClassId.mergedClassId(): ClassId {
  return ClassId(
    packageFqName,
    Name.identifier("Merged" + relativeClassName.asString().capitalizeUS()),
  )
}

public fun ClassId.generatedClass(suffix: String): ClassId {
  return joinSimpleNames(separator = "_", suffix = suffix)
}

public fun Collection<ClassId>.asFqNames(): Collection<FqName> = map { it.asSingleFqName() }
