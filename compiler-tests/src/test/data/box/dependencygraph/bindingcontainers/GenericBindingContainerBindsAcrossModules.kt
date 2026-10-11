// MODULE: lib
// The consuming module must restore the AliasBindings<T> container receiver before replacing T with String.
@BindingContainer
class AliasBindings<T : Any> {
  @Binds val T.bind: Any get() = this
}

// Binary metadata must retain the nested types and V's Comparable<V> bound.
@BindingContainer
class NestedAliasBindings<K : Any, V : Comparable<V>> {
  @Binds
  @Named("nested")
  private fun bindMap(@Named("source") source: Map<K, List<V>>): Map<K, Collection<V>> = source

  @Multibinds(allowEmpty = true) private fun emptyValues(): Set<V> = emptySet()
}

// MODULE: main(lib)
@DependencyGraph
interface AppGraph {
  val value: Any
  @Named("nested") val nestedValues: Map<String, Collection<Int>>
  val emptyValues: Set<Int>

  @Provides fun provideString(): String = "bound value"
  @Provides @Named("source") fun provideMap(): Map<String, List<Int>> = mapOf("numbers" to listOf(1, 2))

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Includes aliases: AliasBindings<String>,
      @Includes nestedAliases: NestedAliasBindings<String, Int>,
    ): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(AliasBindings(), NestedAliasBindings())
  assertEquals("bound value", graph.value)
  assertEquals(mapOf("numbers" to listOf(1, 2)), graph.nestedValues)
  assertTrue(graph.emptyValues.isEmpty())
  return "OK"
}
