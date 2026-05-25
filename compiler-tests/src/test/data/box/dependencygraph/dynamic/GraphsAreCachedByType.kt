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

// FILE: OtherFile.kt
// A call site for the same type in a different file gets its own impl
fun otherFile(): ExampleGraph = createDynamicGraph<ExampleGraph>(Bindings)

// FILE: box.kt
fun box(): String {
  val (graph1, graph2) = sameFile()
  // Same file + same type: one shared impl
  assertEquals(graph1::class.qualifiedName, graph2::class.qualifiedName)
  // Different file: a distinct impl, even for the same type
  // https://github.com/ZacSweers/metro/issues/2324
  assertNotEquals(graph1::class.qualifiedName, otherFile()::class.qualifiedName)
  return "OK"
}
