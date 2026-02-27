// ENABLE_KCLASS_TO_CLASS_INTEROP

interface Greeting

class HelloGreeting : Greeting

class GoodbyeGreeting : Greeting

@DependencyGraph
interface ExampleGraph {
  @Provides @IntoMap @ClassKey(HelloGreeting::class) fun provideHello(): Greeting = HelloGreeting()

  @Provides
  @IntoMap
  @ClassKey(GoodbyeGreeting::class)
  fun provideGoodbye(): Greeting = GoodbyeGreeting()

  val greetings: Map<Class<*>, Provider<Greeting>>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val map = graph.greetings
  assertEquals(2, map.size)
  // Verify keys are Class instances and values are providers
  for (key in map.keys) {
    assertTrue(key is Class<*>)
  }
  assertIs<HelloGreeting>(map.getValue(HelloGreeting::class.java)())
  assertIs<GoodbyeGreeting>(map.getValue(GoodbyeGreeting::class.java)())
  return "OK"
}
