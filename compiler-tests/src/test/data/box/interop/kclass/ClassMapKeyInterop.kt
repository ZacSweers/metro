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

  val greetings: Map<Class<*>, Greeting>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val map = graph.greetings
  assertEquals(2, map.size)
  for (key in map.keys) {
    assertTrue(key is Class<*>)
  }
  assertContains(map, HelloGreeting::class.java)
  assertContains(map, GoodbyeGreeting::class.java)
  assertIs<HelloGreeting>(map[HelloGreeting::class.java])
  assertIs<GoodbyeGreeting>(map[GoodbyeGreeting::class.java])
  return "OK"
}
