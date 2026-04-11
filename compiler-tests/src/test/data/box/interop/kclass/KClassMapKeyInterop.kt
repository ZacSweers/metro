// ENABLE_KCLASS_TO_CLASS_INTEROP
import kotlin.reflect.KClass

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

  val greetings: Map<KClass<*>, Greeting>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val map = graph.greetings
  assertEquals(2, map.size)
  // Verify keys are KClass instances
  for (key in map.keys) {
    assertTrue(key is KClass<*>)
  }
  assertContains(map, HelloGreeting::class)
  assertContains(map, GoodbyeGreeting::class)
  assertIs<HelloGreeting>(map[HelloGreeting::class])
  assertIs<GoodbyeGreeting>(map[GoodbyeGreeting::class])
  return "OK"
}
