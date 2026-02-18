// Generic binding container with concrete return type passed via @Includes
@BindingContainer
class TypedBindings<T>(private val value: T) {
  @Provides fun provideString(): String = value.toString()
}

@DependencyGraph
interface AppGraph {
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes stringBindings: TypedBindings<Int>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(TypedBindings(42))
  assertEquals("42", graph.string)
  return "OK"
}
