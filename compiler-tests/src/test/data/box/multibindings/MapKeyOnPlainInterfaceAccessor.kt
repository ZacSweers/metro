import dev.zacsweers.metro.MapKey

@MapKey
annotation class Tag(val value: String)

interface Exposed {
  @Tag("a")
  val value: String
}

@DependencyGraph
interface ExampleGraph : Exposed {
  @Provides fun provideValue(): String = "x"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return if (graph.value == "x") "OK" else "FAIL: ${graph.value}"
}
