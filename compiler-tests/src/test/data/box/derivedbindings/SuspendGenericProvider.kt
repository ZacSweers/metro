// ENABLE_DERIVED_BINDINGS
// ENABLE_SUSPEND_PROVIDERS

// A suspend generic provider resolves through suspend accessors.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@Inject class Consumer(val encoder: Encoder<List<String>>)

@DependencyGraph
interface AppGraph {
  suspend fun consumer(): Consumer

  suspend fun ints(): Encoder<List<Int>>

  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides suspend fun int(): Encoder<Int> = NamedEncoder("int")

  @Provides
  suspend fun <T> list(element: Encoder<T>): Encoder<List<T>> =
    NamedEncoder("list(${element.name})")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return runBlocking {
    assertEquals("list(string)", graph.consumer().encoder.name)
    assertEquals("list(int)", graph.ints().name)
    "OK"
  }
}
