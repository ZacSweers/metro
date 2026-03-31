// ENABLE_KCLASS_TO_CLASS_INTEROP

// Regression test: when Map<Class<*>, V> is injected through an @Inject constructor,
// the factory stores the binding as a Provider<Map<KClass<*>, V>> internally.
// The KClass-to-Class map key conversion must happen after the provider is invoked,
// not before, otherwise it crashes trying to access type arguments on the Provider type.

interface Greeting

class HelloGreeting : Greeting

class GoodbyeGreeting : Greeting

@Inject
class GreetingHolder(val greetings: Map<Class<*>, Greeting>)

@DependencyGraph
interface ExampleGraph {
  @Provides @IntoMap @ClassKey(HelloGreeting::class) fun provideHello(): Greeting = HelloGreeting()

  @Provides
  @IntoMap
  @ClassKey(GoodbyeGreeting::class)
  fun provideGoodbye(): Greeting = GoodbyeGreeting()

  val holder: GreetingHolder
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val map = graph.holder.greetings
  assertEquals(2, map.size)
  for (key in map.keys) {
    assertTrue(key is Class<*>)
  }
  assertIs<HelloGreeting>(map[HelloGreeting::class.java])
  assertIs<GoodbyeGreeting>(map[GoodbyeGreeting::class.java])
  return "OK"
}
