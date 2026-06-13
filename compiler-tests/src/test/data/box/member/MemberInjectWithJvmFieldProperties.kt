// IGNORE_BACKEND: JS_IR

class Example {
  @JvmField @Inject var stringField: String = "Hello"

  @JvmField @Inject var longField: Long = 3L
}

@DependencyGraph
interface AppGraph {
  fun inject(example: Example)
}

fun box(): String {
  val example = Example()
  createGraph<AppGraph>().inject(example)
  assertEquals("Hello", example.stringField)
  assertEquals(3L, example.longField)
  return "OK"
}
