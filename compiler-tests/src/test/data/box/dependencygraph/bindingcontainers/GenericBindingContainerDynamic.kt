// Generic binding container with createDynamicGraph
@DependencyGraph
interface AppGraph {
  val value: Long

  @Provides fun defaultValue(): Long = 0L
}

@BindingContainer
class DynamicValueBindings<T : Number>(private val value: T) {
  @Provides fun provideLong(): Long = value.toLong()
}

fun box(): String {
  val graph = createDynamicGraph<AppGraph>(DynamicValueBindings(42))
  assertEquals(42L, graph.value)
  return "OK"
}
