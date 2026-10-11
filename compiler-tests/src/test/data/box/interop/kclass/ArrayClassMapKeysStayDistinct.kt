// ENABLE_KCLASS_TO_CLASS_INTEROP

import kotlin.reflect.KClass

@MapKey annotation class FirstClassKey(val value: KClass<*>)
@MapKey annotation class SecondClassKey(val value: KClass<*>)

@DependencyGraph
interface ExampleGraph {
  val kotlinClasses: Map<KClass<*>, String>
  val javaClasses: Map<Class<*>, String>

  // JVM array class literals retain the component class and each array dimension.
  @Provides @IntoMap @FirstClassKey(Array<String>::class)
  fun strings(): String = "strings"

  @Provides @IntoMap @SecondClassKey(Array<Int>::class)
  fun ints(): String = "ints"

  @Provides @IntoMap @ClassKey(Array<Array<String>>::class)
  fun nestedStrings(): String = "nested strings"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val expected: Map<Class<*>, String> = mapOf(
    arrayOf<String>().javaClass to "strings",
    arrayOf<Int>().javaClass to "ints",
    arrayOf<Array<String>>().javaClass to "nested strings",
  )
  assertEquals(expected, graph.javaClasses)
  val expectedKotlin: Map<KClass<*>, String> = expected.mapKeys { (key, _) -> key.kotlin }
  assertEquals(expectedKotlin, graph.kotlinClasses)
  return "OK"
}
