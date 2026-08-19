// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.Locale
import org.jetbrains.kotlin.name.Name

public fun String.capitalizeUS(): String = replaceFirstChar {
  if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
}

public fun String.decapitalizeUS(): String = replaceFirstChar { it.lowercase(Locale.US) }

public fun Name.decapitalizeUS(): Name = asString().decapitalizeUS().asName()

public fun String.asName(): Name = Name.identifier(this)

public const val HASH_SUFFIX_LENGTH: Int = 5

private const val HEX_CHARS = HASH_SUFFIX_LENGTH - 1
private const val HEX_BITS = HEX_CHARS * 4
private const val HEX_MASK = (1 shl HEX_BITS) - 1

/**
 * Computes a unique, deterministic suffix string derived from the object's hash code. This suffix
 * is Java identifier-safe and file name-safe, ensuring compatibility across use cases where such
 * constraints are necessary.
 *
 * The suffix is a combination of a lowercase alphabetic character followed by a fixed-length hex
 * representation of a portion of the hash code (length is [HASH_SUFFIX_LENGTH]).
 */
public val Any.hashSuffix: String
  get() {
    val hash = hashCode()
    // Letter + (HASH_SUFFIX_LENGTH - 1) hex chars
    val first = 'a' + ((hash ushr HEX_BITS) and 0xff) % 26
    val rest = hash and HEX_MASK
    return first + rest.toString(16).padStart(HEX_CHARS, '0')
  }
