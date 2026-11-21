/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.renderer.KeywordStringsGenerated.KEYWORDS

/**
 * Cross-platform reserved keywords that must be avoided in generated identifiers.
 *
 * This set contains reserved keywords from JavaScript (ES5/ES6), C99, and Objective-C that are NOT
 * already included in Kotlin's KEYWORDS constant. By pre-allocating these keywords, we ensure
 * generated code compiles successfully on all Kotlin multiplatform targets (JVM, JS, Native).
 *
 * Sources:
 * - JavaScript: ECMAScript 5.1 spec (https://262.ecma-international.org/5.1/#sec-7.6)
 * - C99: ISO/IEC 9899:1999 C Standard
 * - Objective-C: Apple Objective-C Documentation + Clang keywords
 *
 * Many keywords overlap across platforms (e.g., `break`, `case`, `for`), so this set only contains
 * platform-specific keywords not already blocked by Kotlin.
 */
private val CROSS_PLATFORM_RESERVED_KEYWORDS =
  setOf(
    // JavaScript-specific keywords and special identifiers
    // (not already in Kotlin KEYWORDS)
    "arguments",
    "await",
    "debugger",
    "delete",
    "eval",
    "function",
    "in",
    "instanceof",
    "let",
    "typeof",
    "var",
    "void",
    "with",
    "yield",

    // C99-specific keywords (not already in Kotlin KEYWORDS)
    "auto",
    "char",
    "double",
    "extern",
    "float",
    "goto",
    "inline",
    "int",
    "long",
    "register",
    "restrict",
    "short",
    "signed",
    "sizeof",
    "struct",
    "typedef",
    "union",
    "unsigned",
    "volatile",
    "_Bool",
    "_Complex",
    "_Imaginary",

    // Objective-C-specific keywords (not already in Kotlin KEYWORDS)
    "id",
    "nil",
    "Nil",
    "YES",
    "NO",
    "SEL",
    "IMP",
    "BOOL",
    "instancetype",
    "required",
    "optional",
    "synthesize",
    "dynamic",
    "readonly",
    "readwrite",
    "assign",
    "retain",
    "copy",
    "nonatomic",
    "atomic",
    "strong",
    "weak",
  )

private const val SAFE_CODE = '_'.code
private const val FIRST_SUFFIX = 2
private val RESERVED_NAMES = (KEYWORDS + CROSS_PLATFORM_RESERVED_KEYWORDS).associateWith { FIRST_SUFFIX }

/**
 * Allocates unique identifiers for generated declarations. [mode] controls the suffix on a
 * collision. A tag lets [get] look up an allocated name later.
 */
internal class NameAllocator
private constructor(
  allocatedNames: Map<String, Int>,
  private val tagToName: ConcurrentHashMap<Any, String>,
  private val mode: Mode,
) {
  /** Reserves keywords across Kotlin targets when [preallocateKeywords] is enabled. */
  constructor(
    preallocateKeywords: Boolean = true,
    mode: Mode = Mode.UNDERSCORE,
  ) : this(
    allocatedNames =
      if (preallocateKeywords) {
        RESERVED_NAMES
      } else {
        emptyMap()
      },
    tagToName = ConcurrentHashMap(),
    mode = mode,
  )

  // Each value is the first numeric suffix still worth checking for its name.
  private val allocatedNames = ConcurrentHashMap(allocatedNames)

  fun allocatedNames(): Set<String> = allocatedNames.keys

  fun reserveName(suggestedName: String, tag: Any? = null) {
    @Suppress("RETURN_VALUE_NOT_USED")
    newNameImpl(suggestedName, tag, generateNewIfExisting = false)
  }

  /** Sanitizes [suggestion] and claims a unique name. Each [tag] can be used once. */
  fun newName(suggestion: String, tag: Any? = null): String {
    return newNameImpl(suggestion, tag, generateNewIfExisting = true)
  }

  private fun newNameImpl(suggestion: String, tag: Any?, generateNewIfExisting: Boolean): String {
    val cleanedSuggestion = toSafeIdentifier(suggestion)
    if (tag == null) {
      return allocate(cleanedSuggestion, generateNewIfExisting)
    }
    return tagToName.compute(tag) { _, existing ->
      require(existing == null) { "tag $tag cannot be used for both '$existing' and '$suggestion'" }
      allocate(cleanedSuggestion, generateNewIfExisting)
    }!!
  }

  /**
   * Claims a name based on [cleanedSuggestion] in [allocatedNames]. If the name is taken and
   * [generateNewIfExisting] is false, this returns the taken name without claiming a new one.
   */
  private fun allocate(cleanedSuggestion: String, generateNewIfExisting: Boolean): String {
    val first = allocatedNames.putIfAbsent(cleanedSuggestion, FIRST_SUFFIX)
    if (first == null || !generateNewIfExisting) {
      return cleanedSuggestion
    }

    var suffix = first
    var candidate = cleanedSuggestion
    do {
      candidate = when (mode) {
        Mode.UNDERSCORE -> candidate + "_"
        Mode.COUNT -> cleanedSuggestion + suffix++
      }
    } while (allocatedNames.putIfAbsent(candidate, FIRST_SUFFIX) != null)

    if (mode == Mode.COUNT) {
      // Names stay claimed. A concurrent allocation may have advanced the suffix already.
      allocatedNames.replace(cleanedSuggestion, first, suffix)
    }

    return candidate
  }

  /** Retrieve a name created with [NameAllocator.newName]. */
  operator fun get(tag: Any): String = requireNotNull(tagToName[tag]) { "unknown tag: $tag" }

  /** Copies the current reservations and tags for an independent scope. */
  fun copy(): NameAllocator {
    return NameAllocator(allocatedNames, ConcurrentHashMap(tagToName), mode = mode)
  }

  internal enum class Mode {
    UNDERSCORE,
    COUNT,
  }
}

/**
 * Sanitizes a suggestion string to be a valid cross-platform identifier.
 *
 * Sanitization rules (applied in order):
 * 1. Replaces dangerous characters (`.`, `;`, `/`, `<`, `>`, `[`, `]`) with `_`
 * 2. Replaces non-ASCII characters (codePoint > 127) with `_` for cross-platform safety
 * 3. Replaces other invalid identifier characters with `_`
 * 4. Prepends `_` if the identifier starts with a digit or other invalid start character
 *
 * Examples:
 * - `foo.bar` → `foo_bar` (dangerous character)
 * - `café` → `caf_` (non-ASCII)
 * - `123abc` → `_123abc` (starts with digit)
 * - `foo<T>` → `foo_T_` (dangerous characters)
 */
// TODO would be nice to handle `foo<*>` -> `foo_star_`
internal fun toSafeIdentifier(suggestion: String) = buildString {
  var i = 0
  while (i < suggestion.length) {
    val codePoint = suggestion.codePointAt(i)

    // Check if we need to prepend underscore at the start
    if (
      i == 0 &&
        !Character.isJavaIdentifierStart(codePoint) &&
        Character.isJavaIdentifierPart(codePoint)
    ) {
      append("_")
    }

    // Determine the valid code point to use
    val validCodePoint: Int =
      when {
        // Block non-ASCII for cross-platform compatibility (code point > 127)
        codePoint > 127 -> SAFE_CODE
        // Explicitly block dangerous characters before checking java identifier allowances
        else ->
          when (codePoint) {
            46, // .
            59, // ;
            47, // /
            60, // <
            62, // >
            91, // [
            93 // ]
            -> SAFE_CODE
            else ->
              if (Character.isJavaIdentifierPart(codePoint)) {
                codePoint
              } else {
                SAFE_CODE
              }
          }
      }

    appendCodePoint(validCodePoint)
    i += Character.charCount(codePoint)
  }
}

internal fun NameAllocator.newName(suggestion: Name, tag: Any? = null): Name {
  return newName(suggestion.asString(), tag).asName()
}

internal fun NameAllocator.reserveName(suggestion: Name, tag: Any? = null) {
  return reserveName(suggestion.asString(), tag)
}
