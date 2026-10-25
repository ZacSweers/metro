// LANGUAGE: +ContextParameters
// MODULE: lib
class Label(val value: String)

class Greeting(val value: String)

class Shout(val value: String)

@BindingContainer
object ObjectProviders {
  @Provides fun Label.provideGreeting(): Greeting = Greeting("hello $value")

  @get:Provides
  val Greeting.provideShout: Shout
    get() = Shout(value.uppercase())
}

@BindingContainer
class InstanceProviders(private val suffix: String) {
  @Provides fun Greeting.provideText(count: Int): CharSequence = "$value x$count$suffix"
}

@BindingContainer
interface CompanionProviders {
  companion object {
    @Provides fun Int.provideLong(): Long = toLong() * 2
  }
}

@BindingContainer
object QualifiedProviders {
  @Provides
  @Named("mixed")
  context(@Named("prefix") prefix: String)
  fun @receiver:Named("name") String.provideMixed(count: Int): String = "$prefix$this$count"

  @get:Provides
  @get:Named("nameLength")
  val @receiver:Named("name") String.provideNameLength: Int
    get() = length

  @get:Provides
  @get:Named("mixedLength")
  context(@Named("prefix") prefix: String)
  val @receiver:Named("name") String.provideMixedLength: Int
    get() = prefix.length * 10 + this.length
}

// MODULE: main(lib)
@BindingContainer
object LocalProviders {
  @Provides fun Lazy<Label>.provideLocal(): Short = value.value.length.toShort()

  @Provides fun Provider<Greeting>.provideFloat(): Float = this().value.length.toFloat()

  @Provides
  @Named("localMixed")
  context(@Named("prefix") prefix: String)
  fun @receiver:Named("name") String.provideLocalMixed(count: Int): String = "$prefix$this$count"

  @get:Provides
  @get:Named("localMixedLength")
  context(@Named("prefix") prefix: String)
  val @receiver:Named("name") String.provideLocalMixedLength: Int
    get() = prefix.length * 10 + this.length
}

@DependencyGraph(
  bindingContainers =
    [
      ObjectProviders::class,
      CompanionProviders::class,
      QualifiedProviders::class,
      LocalProviders::class,
    ]
)
interface AppGraph {
  val greeting: Greeting
  val shout: Shout
  val text: CharSequence
  val long: Long
  @Named("mixed") val mixed: String
  @Named("nameLength") val nameLength: Int
  @Named("mixedLength") val mixedLength: Int
  @Named("localMixed") val localMixed: String
  @Named("localMixedLength") val localMixedLength: Int
  @Named("graphMixed") val graphMixed: String
  val local: Short
  val float: Float
  val graphValue: Double

  @Provides fun label(): Label = Label("metro")

  @Provides fun count(): Int = 3

  @Provides @Named("prefix") fun prefix(): String = ">"

  @Provides @Named("name") fun name(): String = "name"

  @Provides fun unqualified(): String = "wrong"

  @Provides fun Shout.provideGraphValue(): Double = value.length.toDouble()

  @Provides
  @Named("graphMixed")
  context(@Named("prefix") prefix: String)
  fun @receiver:Named("name") String.provideGraphMixed(): String = "$prefix$this"

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes providers: InstanceProviders): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(InstanceProviders("!"))
  assertEquals("hello metro", graph.greeting.value)
  assertEquals("HELLO METRO", graph.shout.value)
  assertEquals("hello metro x3!", graph.text)
  assertEquals(6L, graph.long)
  assertEquals(">name3", graph.mixed)
  assertEquals(4, graph.nameLength)
  assertEquals(14, graph.mixedLength)
  assertEquals(">name3", graph.localMixed)
  assertEquals(14, graph.localMixedLength)
  assertEquals(">name", graph.graphMixed)
  assertEquals(5.toShort(), graph.local)
  assertEquals(11f, graph.float)
  assertEquals(11.0, graph.graphValue)
  return "OK"
}
