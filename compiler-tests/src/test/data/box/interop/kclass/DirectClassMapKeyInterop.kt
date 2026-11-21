// ENABLE_KCLASS_TO_CLASS_INTEROP
// METRO_JVM_ONLY

import kotlin.reflect.KClass

@Inject class Holder(val values: Map<Class<*>, String>)

@DependencyGraph
interface Graph {
  val values: Map<Class<*>, String>
  val deferred: Lazy<Map<Class<*>, String>>
  val holder: Holder
  val names: Map<KClass<*>, String>
  val converted: Map<Class<*>, Int>
  val missing: Map<Class<*>, Long>?
  val nullableKeys: Map<Class<*>?, Boolean>

  @Provides fun missing(): Map<KClass<*>, Long>? = null
  @Provides fun nullableKeys(): Map<KClass<*>?, Boolean> = mapOf(null to true)
  @Provides fun strings(): Map<Class<*>, String> = mapOf(String::class.java to "value")
  @Provides fun names(): Map<KClass<*>, String> = mapOf(String::class to "name")
  @Provides fun numbers(): Map<KClass<*>, Int> = mapOf(Int::class to 1)
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals("value", graph.values[String::class.java])
  assertEquals("value", graph.deferred.value[String::class.java])
  assertEquals("value", graph.holder.values[String::class.java])
  assertEquals("name", graph.names[String::class])
  assertEquals(1, graph.converted[Int::class.java])
  assertNull(graph.missing)
  assertEquals(true, graph.nullableKeys[null])
  return "OK"
}
