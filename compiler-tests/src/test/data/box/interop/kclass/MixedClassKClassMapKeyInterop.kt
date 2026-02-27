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

  val greetingsClass: Map<Class<*>, Greeting>
  val greetingsKClass: Map<KClass<*>, Greeting>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // Verify Class<*> accessor
  val classMap = graph.greetingsClass
  assertEquals(2, classMap.size)
  for (key in classMap.keys) {
    assertIs<Class<*>>(key)
  }
  assertIs<HelloGreeting>(classMap[HelloGreeting::class.java])
  assertIs<GoodbyeGreeting>(classMap[GoodbyeGreeting::class.java])

  // Verify KClass<*> accessor
  val kclassMap = graph.greetingsKClass
  assertEquals(2, kclassMap.size)
  for (key in kclassMap.keys) {
    assertIs<KClass<*>>(key)
  }
  assertIs<HelloGreeting>(kclassMap[HelloGreeting::class])
  assertIs<GoodbyeGreeting>(kclassMap[GoodbyeGreeting::class])

  return "OK"
}
