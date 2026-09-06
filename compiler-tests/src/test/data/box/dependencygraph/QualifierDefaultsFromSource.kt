@Qualifier
annotation class ScalarKey(val text: String = "", val count: Int = 0, val enabled: Boolean = false)

annotation class Nested(val value: String = "nested", vararg val names: String)

@Qualifier
annotation class NestedKey(val value: Nested = Nested())

@Qualifier
annotation class ArrayKey(val names: Array<String> = ["first", "second"], val numbers: IntArray = [1, 2])

@BindingContainer
object Values {
  @Provides @ScalarKey fun scalar(): String = "scalar"
  @Provides @ScalarKey(count = 1) fun otherScalar(): String = "other scalar"
  @Provides @NestedKey fun nested(): String = "nested"
  @Provides @NestedKey(Nested(names = ["name"])) fun namedNested(): String = "named nested"
  @Provides @ArrayKey fun array(): String = "array"
  @Provides @ArrayKey([], []) fun emptyArrays(): String = "empty arrays"
}

@DependencyGraph(bindingContainers = [Values::class])
interface AppGraph {
  @ScalarKey("", 0, false) val scalar: String
  @ScalarKey val omittedScalar: String
  @ScalarKey("", 1, false) val otherScalar: String
  @NestedKey(Nested("nested", names = [])) val nested: String
  @NestedKey val omittedNested: String
  @NestedKey(Nested("nested", "name")) val namedNested: String
  @ArrayKey(["first", "second"], [1, 2]) val array: String
  @ArrayKey val omittedArray: String
  @ArrayKey([], []) val emptyArrays: String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("scalar", graph.scalar)
  assertEquals(graph.scalar, graph.omittedScalar)
  assertEquals("other scalar", graph.otherScalar)
  assertEquals("nested", graph.nested)
  assertEquals(graph.nested, graph.omittedNested)
  assertEquals("named nested", graph.namedNested)
  assertEquals("array", graph.array)
  assertEquals(graph.array, graph.omittedArray)
  assertEquals("empty arrays", graph.emptyArrays)
  return "OK"
}
