// MIN_JS_COMPILER_VERSION: 2.3.20
// MODULE: lib
@Qualifier
annotation class NamedValue(val value: String)

annotation class NestedValue(val value: String)

@Qualifier
annotation class StructuredValue(
  val nested: NestedValue,
  val names: Array<String>,
  val suffix: String = "default",
)

@BindingContainer
object Values {
  @Provides @NamedValue("Aa") fun first(): String = "first"
  @Provides @NamedValue("BB") fun second(): String = "second"

  @Provides
  @StructuredValue(NestedValue("Aa"), ["Aa", "BB"])
  fun nestedFirst(): String = "nested first"

  @Provides
  @StructuredValue(NestedValue("BB"), ["Aa", "BB"])
  fun nestedSecond(): String = "nested second"

  @Provides
  @StructuredValue(NestedValue("Aa"), ["BB", "Aa"])
  fun arrayOrder(): String = "array order"
}

// MODULE: main(lib)
@DependencyGraph(bindingContainers = [Values::class])
interface AppGraph {
  @NamedValue("Aa") val first: String
  @NamedValue("BB") val second: String

  @StructuredValue(NestedValue("Aa"), ["Aa", "BB"], "default")
  val nestedFirst: String

  @StructuredValue(NestedValue("BB"), ["Aa", "BB"])
  val nestedSecond: String

  @StructuredValue(NestedValue("Aa"), ["BB", "Aa"])
  val arrayOrder: String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("first", graph.first)
  assertEquals("second", graph.second)
  assertEquals("nested first", graph.nestedFirst)
  assertEquals("nested second", graph.nestedSecond)
  assertEquals("array order", graph.arrayOrder)
  return "OK"
}
