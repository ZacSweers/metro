// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.symbols.Symbols

@Poko
internal class StringContextualTypeKey
private constructor(
  override val typeKey: StringTypeKey,
  override val hasDefault: Boolean = false,
  @Poko.Skip override val rawType: String? = null,
  @Poko.Skip override val wrappedType: WrappedType<String>,
) : BaseContextualTypeKey<String, StringTypeKey, StringContextualTypeKey> {

  override fun toString(): String = render(short = true)

  override fun withTypeKey(typeKey: StringTypeKey, rawType: String?): StringContextualTypeKey {
    return create(typeKey, hasDefault = hasDefault, rawType = rawType)
  }

  override fun render(short: Boolean, includeQualifier: Boolean): String = buildString {
    append(
      wrappedType.render { type ->
        if (type == typeKey.type) {
          typeKey.render(short, includeQualifier)
        } else {
          type
        }
      }
    )
    if (hasDefault) {
      append(" = ...")
    }
  }

  companion object {
    fun create(
      typeKey: StringTypeKey,
      hasDefault: Boolean = false,
      rawType: String? = null,
    ): StringContextualTypeKey {
      val wrappedType = parseWrappedType(typeKey.type)
      return StringContextualTypeKey(
        typeKey = StringTypeKey(wrappedType.canonicalType()),
        wrappedType = wrappedType,
        hasDefault = hasDefault,
        rawType = rawType,
      )
    }

    private fun parseWrappedType(type: String): WrappedType<String> {
      return when {
        type.startsWith("Provider<") -> {
          val inner = type.removeSurrounding("Provider<", ">")
          WrappedType.Provider(parseWrappedType(inner), Symbols.ClassIds.metroProvider)
        }

        type.startsWith("Lazy<") -> {
          val inner = type.removeSurrounding("Lazy<", ">")
          WrappedType.Lazy(parseWrappedType(inner), Symbols.ClassIds.Lazy)
        }

        type.startsWith("Map<") -> {
          val inner = type.removeSurrounding("Map<", ">")
          val (keyType, valueType) = inner.split(",").map { it.trim() }
          WrappedType.Map(keyType, parseWrappedType(valueType)) { "Map<$keyType, $valueType>" }
        }

        else -> WrappedType.Canonical(type)
      }
    }
  }
}
