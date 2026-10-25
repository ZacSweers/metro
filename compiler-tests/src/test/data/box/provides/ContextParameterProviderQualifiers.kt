// LANGUAGE: +ContextParameters
// MODULE: lib
class Label(val value: String)

@BindingContainer
object QualifiedProviders {
  @Provides
  @Named("greeting")
  context(@Named("first") first: String, @Named("last") last: String)
  fun provideGreeting(): String = "$first $last"

  @get:Provides
  @get:Named("length")
  context(@Named("first") first: String)
  val firstLength: Int
    get() = first.length
}

@BindingContainer
class InstanceProviders(private val suffix: String) {
  @Provides
  context(@Named("last") last: Provider<String>, label: Lazy<Label>)
  fun provideLabelText(): CharSequence = "${label.value.value}:${last()}$suffix"
}

// MODULE: main(lib)
@BindingContainer
object LocalProviders {
  @Provides
  context(@Named("last") last: String, unqualified: String)
  fun provideLocal(): Long = (last.length * 10 + unqualified.length).toLong()
}

@DependencyGraph(bindingContainers = [QualifiedProviders::class, LocalProviders::class])
interface AppGraph {
  @Named("greeting") val greeting: String
  @Named("length") val length: Int
  val labelText: CharSequence
  val local: Long
  val graphLocal: Double

  @Provides @Named("first") fun first(): String = "Ada"

  @Provides @Named("last") fun last(): String = "Lovelace"

  @Provides fun unqualified(): String = "plain"

  @Provides fun label(): Label = Label("label")

  @Provides
  context(@Named("first") first: String, @Named("greeting") greeting: String)
  fun provideGraphLocal(): Double = (first.length + greeting.length).toDouble()

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes providers: InstanceProviders): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(InstanceProviders("!"))
  assertEquals("Ada Lovelace", graph.greeting)
  assertEquals(3, graph.length)
  assertEquals("label:Lovelace!", graph.labelText)
  assertEquals(85L, graph.local)
  assertEquals(15.0, graph.graphLocal)
  return "OK"
}
