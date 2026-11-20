// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

@Inject
fun QualifiedParameter(@Named("int") int: Int): Int = int

@Named("app")
@Inject
fun QualifiedFunction(int: Int): Int = int

@DependencyGraph
interface ExampleGraph {
  val first: QualifiedParameter
  @Named("app") val second: QualifiedFunction

  @Named("int") @Provides private fun provideQualifiedInt(): Int = 0
  @Provides private fun provideInt(): Int = 0
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(0, graph.first.invoke())
  assertEquals(0, graph.second.invoke())
  return "OK"
}
