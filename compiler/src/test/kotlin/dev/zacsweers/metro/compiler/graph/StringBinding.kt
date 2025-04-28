package dev.zacsweers.metro.compiler.graph

internal class StringBinding(
  override val contextualTypeKey: StringContextualTypeKey,
  override val dependencies: List<StringContextualTypeKey> = emptyList(),
) : BaseBinding<String, StringTypeKey, StringContextualTypeKey> {
  companion object {
    operator fun invoke(
      typeKey: StringTypeKey,
      dependencies: List<StringContextualTypeKey> = emptyList(),
    ) = StringBinding(StringContextualTypeKey(typeKey), dependencies)
  }
}
