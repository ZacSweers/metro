// ENABLE_DERIVED_BINDINGS

// The graph calls each generic provider with the type arguments inferred for the requested key.
fun interface Encoder<T> {
  fun encode(value: T): String
}

@BindingContainer
object JsonEncoders {
  @Provides fun string(): Encoder<String> = Encoder { "\"$it\"" }

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = Encoder { values ->
    values.joinToString(",", "[", "]") { element.encode(it) }
  }

  @Provides
  fun <T : Any> nullable(inner: Encoder<T>): Encoder<T?> = Encoder { value ->
    if (value == null) {
      "null"
    } else {
      inner.encode(value)
    }
  }

  @Provides
  private fun <V> stringMap(value: Encoder<V>): Encoder<Map<String, V>> = Encoder { map ->
    map.entries.joinToString(",", "{", "}") { "\"${it.key}\":${value.encode(it.value)}" }
  }
}

@DependencyGraph(bindingContainers = [JsonEncoders::class])
interface AppGraph {
  val strings: Encoder<List<String>>
  val nullableString: Encoder<String?>
  val nullableStrings: Encoder<List<String?>>
  val stringMap: Encoder<Map<String, List<String>>>
  val stringMapProvider: Provider<Encoder<Map<String, String>>>
}
