// LANGUAGE: +ContextParameters
// MODULE: lib
class Label(val value: String)

class Greeting(val value: String)

class Count(val value: Int)

@BindingContainer
object ObjectProviders {
  @Provides
  context(label: Label)
  fun provideGreeting(): Greeting = Greeting("hello ${label.value}")

  @get:Provides
  context(label: Label)
  val labelLength: Long
    get() = label.value.length.toLong()
}

@BindingContainer
class InstanceProviders(private val offset: Int) {
  @Provides
  context(label: Label)
  fun provideCount(extra: Int): Count = Count(label.value.length + extra + offset)
}

// MODULE: main(lib)
class Summary(val value: String)

class Shout(val value: String)

@BindingContainer
object LocalProviders {
  @Provides
  context(label: Label)
  fun provideShout(greeting: Greeting): Shout = Shout("${greeting.value}!".uppercase())
}

@DependencyGraph(bindingContainers = [ObjectProviders::class, LocalProviders::class])
interface AppGraph {
  val greeting: Greeting
  val labelLength: Long
  val count: Count
  val shout: Shout
  val summary: Summary

  @Provides fun label(): Label = Label("metro")

  @Provides fun extra(): Int = 2

  @Provides
  context(label: Label)
  fun provideSummary(greeting: Greeting): Summary = Summary("${greeting.value}:${label.value}")

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes providers: InstanceProviders): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(InstanceProviders(offset = 10))
  assertEquals("hello metro", graph.greeting.value)
  assertEquals(5L, graph.labelLength)
  assertEquals(17, graph.count.value)
  assertEquals("HELLO METRO!", graph.shout.value)
  assertEquals("hello metro:metro", graph.summary.value)
  return "OK"
}
