// ENABLE_DERIVED_BINDINGS

// A contributed graph extension resolves derived keys from a binding container contributed to its
// parent's scope. The scoped instance lives in the parent.
abstract class LoggedInScope private constructor()

interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Cache<T>(val id: Int)

class Counter {
  var count = 0
}

@ContributesTo(AppScope::class)
@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")

  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()

  @Provides
  @SingleIn(AppScope::class)
  fun <T> cache(counter: Counter): Cache<T> = Cache(++counter.count)
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val counter: Counter
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val encoder: Encoder<List<String>>
  val cache: Cache<List<String>>

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val first = parent.createLoggedInGraph()
  val second = parent.createLoggedInGraph()
  assertEquals("list(string)", first.encoder.name)
  assertSame(first.cache, second.cache)
  assertEquals(1, parent.counter.count)
  return "OK"
}
