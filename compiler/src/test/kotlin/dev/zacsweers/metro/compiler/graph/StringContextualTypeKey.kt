package dev.zacsweers.metro.compiler.graph

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.Symbols

@Poko
internal class StringContextualTypeKey(
  typeKey: StringTypeKey,
  override val hasDefault: Boolean = false,
  override val isIntoMultibinding: Boolean = false,
  @Poko.Skip override val rawType: String? = null,
) : BaseContextualTypeKey<String, StringTypeKey, StringContextualTypeKey> {

  override val wrappedType: WrappedType<String> = run { parseWrappedType(typeKey.type) }

  override val typeKey = StringTypeKey(wrappedType.canonicalType())

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

  override fun toString(): String = render(short = true)

  override fun withTypeKey(typeKey: StringTypeKey, rawType: String?): StringContextualTypeKey {
    return StringContextualTypeKey(typeKey, hasDefault, isIntoMultibinding, rawType)
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
}
