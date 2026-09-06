// METRO_JVM_ONLY

import kotlin.reflect.KClass

@Qualifier
annotation class ArrayClass(val value: KClass<*>)

@BindingContainer
object ArrayValues {
  @Provides @ArrayClass(Array<String>::class) fun strings(): String = "strings"
  @Provides @ArrayClass(Array<Int?>::class) fun ints(): String = "ints"
  @Provides @ArrayClass(Array<Array<String>>::class) fun nested(): String = "nested"
  @Provides @ArrayClass(IntArray::class) fun primitives(): String = "primitives"
  @Provides @ArrayClass(Array<IntArray>::class) fun nestedPrimitives(): String = "nested primitives"
  @Provides @ArrayClass(Array<Any>::class) fun any(): String = "any"
}

// JVM array class qualifiers keep their component types and dimensions.
@DependencyGraph(bindingContainers = [ArrayValues::class])
interface AppGraph {
  @ArrayClass(Array<String>::class) val strings: String
  @ArrayClass(Array<Int>::class) val ints: String
  @ArrayClass(Array<Array<String>>::class) val nested: String
  @ArrayClass(IntArray::class) val primitives: String
  @ArrayClass(Array<IntArray>::class) val nestedPrimitives: String
  @ArrayClass(Array<Any>::class) val any: String
}

fun box(): String {
  val expected = listOf("strings", "ints", "nested", "primitives", "nested primitives", "any")
  val graph = createGraph<AppGraph>()
  assertEquals(expected, listOf(graph.strings, graph.ints, graph.nested, graph.primitives, graph.nestedPrimitives, graph.any))
  return "OK"
}
