// ENABLE_DERIVED_BINDINGS

// MODULE: lib
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Counter {
  var count = 0
}

@BindingContainer
object Encoders {
  @Provides fun counter(): Counter = Counter()

  @Provides
  inline fun <reified T> named(counter: Counter): Encoder<T> =
    NamedEncoder("named(${T::class.simpleName})#${++counter.count}")

  // The graph in another module can't call this, so it calls the factory's newInstance helper.
  @PublishedApi
  @Provides
  internal inline fun <reified T> list(element: Encoder<T>): Encoder<List<T>> =
    NamedEncoder("list(${T::class.simpleName}, ${element.name})")
}

// MODULE: main(lib)
@Inject
class Consumer(
  val strings: Provider<Encoder<List<String>>>,
  val int: () -> Encoder<Int>,
  val lazyInts: Lazy<Encoder<List<Int>>>,
)

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val string: Encoder<String>
  val stringProvider: Provider<Encoder<String>>
  val ints: Encoder<List<Int>>
  val consumer: Consumer
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("named(String)#1", graph.string.name)
  assertEquals("named(String)#1", graph.stringProvider().name)
  assertEquals("list(Int, named(Int)#1)", graph.ints.name)
  val consumer = graph.consumer
  assertEquals("list(String, named(String)#1)", consumer.strings().name)
  assertEquals("named(Int)#1", consumer.int().name)
  assertEquals("list(Int, named(Int)#1)", consumer.lazyInts.value.name)
  return "OK"
}
