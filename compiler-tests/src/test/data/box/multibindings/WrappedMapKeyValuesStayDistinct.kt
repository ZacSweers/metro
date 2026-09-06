// Wrapped map keys retain their annotation type and every argument.
@MapKey(unwrapValue = false)
annotation class FirstKey(val value: String, val id: Int)

@MapKey(unwrapValue = false)
annotation class SecondKey(val value: String, val id: Int)

@DependencyGraph
interface ExampleGraph {
  val first: Map<FirstKey, String>
  val second: Map<SecondKey, String>

  @Provides @IntoMap @FirstKey("shared", 1) fun firstOne(): String = "first one"
  @Provides @IntoMap @FirstKey("shared", 2) fun firstTwo(): String = "first two"
  @Provides @IntoMap @SecondKey("shared", 1) fun secondOne(): String = "second one"
  @Provides @IntoMap @SecondKey("shared", 2) fun secondTwo(): String = "second two"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(
    mapOf(FirstKey("shared", 1) to "first one", FirstKey("shared", 2) to "first two"),
    graph.first,
  )
  assertEquals(
    mapOf(SecondKey("shared", 1) to "second one", SecondKey("shared", 2) to "second two"),
    graph.second,
  )
  return "OK"
}
