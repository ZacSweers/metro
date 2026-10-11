// A closeable parent doesn't track graph extensions that aren't closeable, but it still won't
// create one after it closes.

@GraphExtension
interface ChildGraph {
  val value: String
}

@DependencyGraph
interface AppGraph : CloseableGraph {
  val child: ChildGraph

  @Provides fun provideValue(): String = "value"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("value", graph.child.value)
  graph.close()
  val error = assertFailsWith<IllegalStateException> { graph.child }
  assertEquals("Cannot create a graph extension of AppGraph because it is closed.", error.message)
  return "OK"
}
