// ENABLE_FUNCTION_PROVIDERS
@DependencyGraph
interface ExampleGraph {
  val holder: StringHolder
  val intHolder: IntHolder

  @Provides fun provideString(): String = "Hello"

  @Provides fun provideInt(): Int = 42
}

@Inject class StringHolder(val provider: () -> String)

@Inject class IntHolder(val provider: () -> Int)

@Inject
class GenericConsumer<T>(val provider: () -> T) {
  fun get(): T = provider()
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("Hello", graph.holder.provider())
  assertEquals(42, graph.intHolder.provider())
  return "OK"
}
