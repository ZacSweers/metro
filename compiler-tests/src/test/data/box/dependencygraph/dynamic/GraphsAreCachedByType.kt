// FILE: SameFile.kt
@DependencyGraph
interface ExampleGraph

@BindingContainer
object Bindings

// Two call sites in the same file share one generated impl
fun sameFile(
  graph: ExampleGraph = createDynamicGraph<ExampleGraph>(Bindings),
  graph2: ExampleGraph = createDynamicGraph<ExampleGraph>(Bindings),
): Pair<ExampleGraph, ExampleGraph> = graph to graph2

@BindingContainer
class TextBindings(private val value: String) {
  @Provides fun text(): String = value
}

@BindingContainer
class NumberBindings(private val value: Int) {
  @Provides fun number(): Int = value
}

val evaluationOrder = mutableListOf<String>()

fun textBindings(value: String): TextBindings {
  evaluationOrder += value
  return TextBindings(value)
}

fun numberBindings(value: Int): NumberBindings {
  evaluationOrder += value.toString()
  return NumberBindings(value)
}

@DependencyGraph
interface OrderedGraph {
  val text: String
  val number: Int

  @Provides fun text(): String = "default"
  @Provides fun number(): Int = 0
}

fun reorderedGraphs(): Pair<OrderedGraph, OrderedGraph> {
  val first = createDynamicGraph<OrderedGraph>(textBindings("first"), numberBindings(1))
  val second = createDynamicGraph<OrderedGraph>(numberBindings(2), textBindings("second"))
  return first to second
}

// Property initializers need the same argument-order handling as function bodies.
val propertyGraph = createDynamicGraph<OrderedGraph>(numberBindings(3), textBindings("property"))

class GraphHolder {
  val graph = createDynamicGraph<OrderedGraph>(numberBindings(4), textBindings("member"))
}

@DependencyGraph
interface OrderedFactoryGraph {
  val text: String
  val number: Int

  @Provides fun text(): String = "default"
  @Provides fun number(): Int = 0

  @DependencyGraph.Factory
  interface Factory {
    fun create(): OrderedFactoryGraph
  }
}

fun reorderedFactories(): Pair<OrderedFactoryGraph.Factory, OrderedFactoryGraph.Factory> {
  val first =
    createDynamicGraphFactory<OrderedFactoryGraph.Factory>(
      textBindings("factory-first"),
      numberBindings(5),
    )
  val second =
    createDynamicGraphFactory<OrderedFactoryGraph.Factory>(
      numberBindings(6),
      textBindings("factory-second"),
    )
  return first to second
}

// FILE: OtherFile.kt
// A call site for the same type in a different file gets its own impl
fun otherFile(): ExampleGraph = createDynamicGraph<ExampleGraph>(Bindings)

fun otherFileFactory(): OrderedFactoryGraph.Factory =
  createDynamicGraphFactory<OrderedFactoryGraph.Factory>(TextBindings("other"), NumberBindings(7))

// FILE: Aa.kt
// These sibling file names have the same hash and still need separate impl names.
fun aaFile(): OrderedGraph =
  createDynamicGraph<OrderedGraph>(TextBindings("Aa"), NumberBindings(8))

// FILE: BB.kt
fun bbFile(): OrderedGraph =
  createDynamicGraph<OrderedGraph>(TextBindings("BB"), NumberBindings(9))

// FILE: box.kt
fun box(): String {
  val (graph1, graph2) = sameFile()
  // Same file + same type: one shared impl
  assertEquals(graph1::class, graph2::class)
  // Different file: a distinct impl, even for the same type
  // https://github.com/ZacSweers/metro/issues/2324
  assertNotEquals(graph1::class, otherFile()::class)

  evaluationOrder.clear()
  val (first, second) = reorderedGraphs()
  assertEquals(listOf("first", "1", "2", "second"), evaluationOrder)
  assertEquals(first::class, second::class)
  assertEquals("first", first.text)
  assertEquals(1, first.number)
  assertEquals("second", second.text)
  assertEquals(2, second.number)
  assertEquals(first::class, propertyGraph::class)
  assertEquals("property", propertyGraph.text)
  assertEquals(3, propertyGraph.number)

  evaluationOrder.clear()
  val member = GraphHolder().graph
  assertEquals(listOf("4", "member"), evaluationOrder)
  assertEquals(first::class, member::class)
  assertEquals("member", member.text)
  assertEquals(4, member.number)

  evaluationOrder.clear()
  val (firstFactory, secondFactory) = reorderedFactories()
  assertEquals(listOf("factory-first", "5", "6", "factory-second"), evaluationOrder)
  assertEquals(firstFactory::class, secondFactory::class)
  assertNotEquals(firstFactory::class, otherFileFactory()::class)
  val firstCreated = firstFactory.create()
  val secondCreated = secondFactory.create()
  assertEquals("factory-first", firstCreated.text)
  assertEquals(5, firstCreated.number)
  assertEquals("factory-second", secondCreated.text)
  assertEquals(6, secondCreated.number)

  val aa = aaFile()
  val bb = bbFile()
  assertNotEquals(aa::class, bb::class)
  assertEquals("Aa", aa.text)
  assertEquals(8, aa.number)
  assertEquals("BB", bb.text)
  assertEquals(9, bb.number)
  return "OK"
}
