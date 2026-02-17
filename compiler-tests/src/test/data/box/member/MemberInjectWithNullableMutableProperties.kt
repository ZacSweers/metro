class Example {
  @JvmField
  @Inject
  var nullableFieldInject: String? = null

  @Inject
  var nullablePropertyInject: Int? = null

  @JvmField
  @Inject
  var fieldInject: Long = 3L

  @Inject
  var propertyInject: Boolean = true
}

@DependencyGraph
interface AppGraph {
  fun inject(example: Example)
}

fun box(): String {
  val example = Example()
  createGraph<AppGraph>().inject(example)
  assertEquals(3L, example.fieldInject)
  assertTrue(example.propertyInject)
  assertNull(example.nullableFieldInject)
  assertNull(example.nullablePropertyInject)
  return "OK"
}