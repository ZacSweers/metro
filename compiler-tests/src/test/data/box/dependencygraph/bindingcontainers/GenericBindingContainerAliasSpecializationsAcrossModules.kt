// MODULE: lib
@BindingContainer
class AliasBindings<T : Any> {
  @Binds val T.bind: Any get() = this
}

// MODULE: main(lib)
@DependencyGraph
interface StringGraph {
  val value: Any

  @Provides fun provideString(): String = "string value"

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes aliases: AliasBindings<String>): StringGraph
  }
}

@DependencyGraph
interface IntGraph {
  val value: Any

  @Provides fun provideInt(): Int = 42

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes aliases: AliasBindings<Int>): IntGraph
  }
}

fun box(): String {
  // Caching the library's AliasBindings<String> mustn't make IntGraph request a String.
  val stringGraph = createGraphFactory<StringGraph.Factory>().create(AliasBindings())
  val intGraph = createGraphFactory<IntGraph.Factory>().create(AliasBindings())
  assertEquals("string value", stringGraph.value)
  assertEquals(42, intGraph.value)
  return "OK"
}
