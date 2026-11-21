class Batch<T>(item: T) : List<List<T>> by listOf(listOf(item))

class Items : Set<Int> by setOf(1)

@DependencyGraph
interface Graph {
  @Provides @ElementsIntoSet fun batch(): Batch<String> = Batch("item")
  @Provides @ElementsIntoSet fun items(): Items = Items()
  @Provides @ElementsIntoSet fun nullableElements(): List<String?> = listOf(null)

  val nested: Set<List<String>>
  val numbers: Set<Int>
  val nullable: Set<String?>
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals(setOf(listOf("item")), graph.nested)
  assertEquals(setOf(1), graph.numbers)
  assertEquals(setOf<String?>(null), graph.nullable)
  return "OK"
}
