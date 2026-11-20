// WITH_COROUTINES
// ENABLE_FUNCTION_PROVIDERS: false

private var output = ""

@DependencyGraph
interface ExampleGraph {
  val unitFunction: () -> Unit
  val intFunction: () -> Int
  val intIntFunction: (Int) -> Int
  val floatReceiverFloatFunction: Float.() -> Float
  val suspendBooleanFunction: suspend () -> Boolean

  @Provides fun provideUnitFunction(): () -> Unit = { output = "Hello, world!" }
  @Provides fun provideIntFunction(): () -> Int = { 2 }
  @Provides fun provideIntIntFunction(): (Int) -> Int = { 2 * it }
  @Provides fun provideFloatReceiverFloatFunction(): Float.() -> Float = { 2 * this }
  @Provides fun provideSuspendBooleanFunction(): suspend () -> Boolean = { true }
}

fun box(): String = runBlocking {
  val graph = createGraph<ExampleGraph>()
  graph.unitFunction()
  assertEquals("Hello, world!", output)
  assertEquals(2, graph.intFunction())
  assertEquals(4, graph.intIntFunction(2))
  assertEquals(4f, graph.floatReceiverFloatFunction(2f))
  assertTrue(graph.suspendBooleanFunction())
  "OK"
}
