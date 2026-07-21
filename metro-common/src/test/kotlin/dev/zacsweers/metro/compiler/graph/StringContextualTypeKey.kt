// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.drewhamilton.poko.Poko
import org.jetbrains.kotlin.name.ClassId

@Poko
internal class StringContextualTypeKey
private constructor(
  override val typeKey: StringTypeKey,
  override val hasDefault: Boolean = false,
  @Poko.Skip override val rawType: String? = null,
  @Poko.Skip override val wrappedType: WrappedType<String>,
) : BaseContextualTypeKey<String, StringTypeKey, StringContextualTypeKey> {

  override fun toString(): String = render(short = true)

  override fun render(
    short: Boolean,
    includeQualifier: Boolean,
    useRelativeClassNames: Boolean,
  ): String = buildString {
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

    private val PROVIDER_CLASS_ID = ClassId.fromString("dev/zacsweers/metro/Provider")
    private val LAZY_CLASS_ID = ClassId.fromString("kotlin/Lazy")

    private fun parseWrappedType(type: String): WrappedType<String> {
      return when {
        type.startsWith("() ->") -> {
          val inner = type.removePrefix("() -> ")
          WrappedType.Provider(parseWrappedType(inner), PROVIDER_CLASS_ID)
        }

        type.startsWith("Lazy<") -> {
          val inner = type.removeSurrounding("Lazy<", ">")
          WrappedType.Lazy(parseWrappedType(inner), LAZY_CLASS_ID)
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
