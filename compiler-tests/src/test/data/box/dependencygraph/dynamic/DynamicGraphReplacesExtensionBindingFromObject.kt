// MODULE: app
@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String
  val subGraph: SubGraph
}

@GraphExtension
interface SubGraph {
  val string: String
}

@BindingContainer
@ContributesTo(AppScope::class)
object DefaultBinding {
  @Provides fun string(): String = "abc"
}

// MODULE: test(app)
@BindingContainer
object TestBinding {
  @Provides fun string(): String = "xyz"
}

fun box(): String {
  val container = TestBinding
  val graph = createDynamicGraph<AppGraph>(container)
  assertEquals("xyz", graph.string)
  val subGraph = graph.subGraph
  assertEquals("xyz", subGraph.string)
  return "OK"
}
