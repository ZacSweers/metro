// A scoped generic provider in a graph with extensions doesn't publish its pattern key to them.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@ContributesTo(AppScope::class)
@BindingContainer
object EncoderBindings {
  @SingleIn(AppScope::class) @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @SingleIn(AppScope::class)
  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")
}

@GraphExtension
interface ChildGraph {
  val stringEncoder: Encoder<String>
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val stringEncoder: Encoder<String>
  val childGraph: ChildGraph
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertSame(graph.stringEncoder, graph.childGraph.stringEncoder)
  assertEquals("string", graph.childGraph.stringEncoder.name)
  return "OK"
}
