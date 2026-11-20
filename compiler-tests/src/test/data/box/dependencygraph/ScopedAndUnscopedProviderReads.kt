@DependencyGraph(AppScope::class)
abstract class ExampleGraph {

  private var scopedCounter = 0
  private var unscopedCounter = 0

  @Named("scoped")
  abstract val scoped: String

  @Named("unscoped")
  abstract val unscoped: String

  @SingleIn(AppScope::class)
  @Provides
  @Named("scoped")
  fun provideScoped(): String = "text " + scopedCounter++

  @Provides
  @Named("unscoped")
  fun provideUnscoped(): String = "text " + unscopedCounter++
}

@Inject
class ExampleClass(@Named("hello") private val text: String)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  // Scoped reads reuse the value. Unscoped reads invoke the provider each time.
  assertEquals(listOf("text 0", "text 0"), List(2) { graph.scoped })
  assertEquals(listOf("text 0", "text 1"), List(2) { graph.unscoped })
  return "OK"
}
