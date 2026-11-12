// ENABLE_DERIVED_BINDINGS

// A generic provider scoped to a graph extension's own scope lives in the extension. Each extension
// instance gets its own instance per key. Its dependencies come from the parent's unscoped generic
// providers.
abstract class ChildScope private constructor()

interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Holder<T>(val encoder: Encoder<T>, val id: Int)

class Counter {
  var count = 0
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val counter: Counter
  val childFactory: ChildGraph.Factory

  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()

  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val holder: Holder<List<String>>
  val holderAgain: Holder<List<String>>
  val stringHolder: Holder<String>

  @Provides
  @SingleIn(ChildScope::class)
  fun <T> holder(encoder: Encoder<T>, counter: Counter): Holder<T> =
    Holder(encoder, ++counter.count)

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val first = parent.childFactory.create()
  val second = parent.childFactory.create()
  val holder = first.holder
  assertEquals("list(string)", holder.encoder.name)
  assertSame(holder, first.holderAgain)
  assertNotSame(holder, second.holder)
  assertNotSame<Any>(holder, first.stringHolder)
  assertEquals("string", first.stringHolder.encoder.name)
  assertEquals(3, parent.counter.count)
  return "OK"
}
