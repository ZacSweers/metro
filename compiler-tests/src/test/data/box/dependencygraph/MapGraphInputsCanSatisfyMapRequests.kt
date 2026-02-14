// https://github.com/ZacSweers/metro/issues/1816

@DependencyGraph
interface FooComponent {
  val value: Int

  @Provides fun provideFoo(bar: Map<String, String>): Int = 3

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides bar: Map<String, String>): FooComponent
  }
}

fun box(): String {
  assertEquals(
    3,
    createGraphFactory<FooComponent.Factory>()
      .create(bar = emptyMap())
      // ClassCastException
      .value,
  )
  return "OK"
}
