// ENABLE_KCLASS_TO_CLASS_INTEROP

interface Greeting

class HelloGreeting : Greeting

@DependencyGraph
interface ExampleGraph {
  @Provides @IntoMap @ClassKey(HelloGreeting::class) fun provideHello(): Greeting = HelloGreeting()

  val greetings: Map<Class<*>, Greeting>

  val providerOfGreetings: () -> Map<Class<*>, Greeting>

  val lazyOfGreetings: Lazy<Map<Class<*>, Greeting>>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  assertIs<HelloGreeting>(graph.greetings[HelloGreeting::class.java])
  assertIs<HelloGreeting>(graph.providerOfGreetings()[HelloGreeting::class.java])
  assertIs<HelloGreeting>(graph.lazyOfGreetings.value[HelloGreeting::class.java])

  return "OK"
}
