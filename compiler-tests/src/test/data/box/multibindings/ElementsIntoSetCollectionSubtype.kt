class Batch<T>(item: T) : List<List<T>> by listOf(listOf(item))

class UserIds : ArrayList<Long>()

class Items<T>(item: T) : List<T> by listOf(item)

@DependencyGraph
interface Graph {
  @Provides @ElementsIntoSet fun batch(): Set<List<String>> = Batch("item").toSet()
  @Provides @ElementsIntoSet fun items(): Items<Int> = Items(1)
  @Provides fun ids(): UserIds = UserIds().apply { add(42L) }
  @Provides @ElementsIntoSet fun userIds(ids: UserIds): Set<Long> = ids.toSet()
  @Provides @ElementsIntoSet fun nullableElements(): List<String?> = listOf(null)

  val nested: Set<List<String>>
  val numbers: Set<Int>
  val userIds: Set<Long>
  val nullable: Set<String?>
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals(setOf(listOf("item")), graph.nested)
  assertEquals(setOf(1), graph.numbers)
  assertEquals(setOf(42L), graph.userIds)
  assertEquals(setOf<String?>(null), graph.nullable)
  return "OK"
}
