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
@file:OptIn(ExperimentalUuidApi::class)

package dev.zacsweers.metro.compiler

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
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
private val RESERVED_KEYWORDS = KEYWORDS + CROSS_PLATFORM_RESERVED_KEYWORDS

/**
 * Assigns Kotlin identifier names to avoid collisions, keywords, and invalid characters. To use,
 * first create an instance and allocate all of the names that you need. Typically this is a mix of
 * user-supplied names and constants:
 * ```kotlin
 * val nameAllocator = NameAllocator()
 * for (property in properties) {
 *   nameAllocator.newName(property.name, property)
 * }
 * nameAllocator.newName("sb", "string builder")
 * ```
 *
 * Pass a unique tag object to each allocation. The tag scopes the name, and can be used to look up
 * the allocated name later. Typically the tag is the object that is being named. In the above
 * example we use `property` for the user-supplied property names, and `"string builder"` for our
 * constant string builder.
 *
 * Once we've allocated names we can use them when generating code:
 * ```kotlin
 * val builder = FunSpec.builder("toString")
 *     .addModifiers(KModifier.OVERRIDE)
 *     .returns(String::class)
 *
 * builder.addStatement("val %N = %T()",
 *     nameAllocator.get("string builder"), StringBuilder::class)
 *
 * for (property in properties) {
 *   builder.addStatement("%N.append(%N)",
 *       nameAllocator.get("string builder"), nameAllocator.get(property))
 * }
 * builder.addStatement("return %N.toString()", nameAllocator.get("string builder"))
 * return builder.build()
 * ```
 *
 * The above code generates unique names if presented with conflicts. Given user-supplied properties
 * with names `ab` and `sb` this generates the following:
 * ```kotlin
 * override fun toString(): kotlin.String {
 *   val sb_ = java.lang.StringBuilder()
 *   sb_.append(ab)
 *   sb_.append(sb)
 *   return sb_.toString()
 * }
 * ```
 *
 * The underscore is appended to `sb` to avoid conflicting with the user-supplied `sb` property.
 * Underscores are also prefixed for names that start with a digit, and used to replace name-unsafe
 * characters like space or dash.
 *
 * When dealing with multiple independent inner scopes, use a [copy][NameAllocator.copy] of the
 * NameAllocator used for the outer scope to further refine name allocation for a specific inner
 * scope.
 *
 * Changes from upstream: added [Mode] support for use with member inject parameters.
 */
// TODO change to Name?
internal class NameAllocator
private constructor(
  private val allocatedNames: MutableSet<String>,
  private val tagToName: MutableMap<Any, String>,
  private val mode: Mode,
) {
  /**
   * @param preallocateKeywords If true, all Kotlin keywords will be preallocated. Requested names
   *   which collide with keywords will be suffixed with underscores to avoid being used as
   *   identifiers:
   * ```kotlin
   * val nameAllocator = NameAllocator(preallocateKeywords = true)
   * println(nameAllocator.newName("when")) // prints "when_"
   * ```
   *
   * If false, keywords will not get any special treatment:
   * ```kotlin
   * val nameAllocator = NameAllocator(preallocateKeywords = false)
   * println(nameAllocator.newName("when")) // prints "when"
   * ```
   *
   * Note that you can use the `%N` placeholder when emitting a name produced by [NameAllocator] to
   * ensure it's properly escaped for use as an identifier:
   * ```kotlin
   * val nameAllocator = NameAllocator(preallocateKeywords = false)
   * println(CodeBlock.of("%N", nameAllocator.newName("when"))) // prints "`when`"
   * ```
   *
   * The default behaviour of [NameAllocator] is to preallocate keywords - this is the behaviour
   * you'll get when using the no-arg constructor.
   */
  constructor(
    preallocateKeywords: Boolean = true,
    mode: Mode = Mode.UNDERSCORE,
  ) : this(
    allocatedNames =
      if (preallocateKeywords) {
        RESERVED_KEYWORDS.toMutableSet()
      } else {
        mutableSetOf()
      },
    tagToName = mutableMapOf(),
    mode = mode,
  )

  /**
   * Return a new name using [suggestion] that will not be a Java identifier or clash with other
   * names. The returned value can be queried multiple times by passing `tag` to
   * [NameAllocator.get].
   */
  fun newName(suggestion: String, tag: Any = Uuid.random().toString()): String {
    val cleanedSuggestion = toSafeIdentifier(suggestion)
    val result = buildString {
      append(cleanedSuggestion)
      var count = 1
      while (!allocatedNames.add(toString())) {
        when (mode) {
          Mode.UNDERSCORE -> append('_')
          Mode.COUNT -> {
            deleteRange(cleanedSuggestion.length, length)
            append(++count)
          }
        }
      }
    }

    val replaced = tagToName.put(tag, result)
    if (replaced != null) {
      tagToName[tag] = replaced // Put things back as they were!
      throw IllegalArgumentException("tag $tag cannot be used for both '$replaced' and '$result'")
    }

    return result
  }

  /** Retrieve a name created with [NameAllocator.newName]. */
  operator fun get(tag: Any): String = requireNotNull(tagToName[tag]) { "unknown tag: $tag" }

  /**
   * Create a deep copy of this NameAllocator. Useful to create multiple independent refinements of
   * a NameAllocator to be used in the respective definition of multiples, independently-scoped,
   * inner code blocks.
   *
   * @return A deep copy of this NameAllocator.
   */
  fun copy(): NameAllocator {
    return NameAllocator(allocatedNames.toMutableSet(), tagToName.toMutableMap(), mode = mode)
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

internal fun NameAllocator.newName(suggestion: Name, tag: Any = Uuid.random().toString()): Name {
  return newName(suggestion.asString(), tag).asName()
}
