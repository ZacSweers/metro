// A grandchild graph extension uses its grandparent's scoped and unscoped generic providers. The
// scoped instances still live in the grandparent.
abstract class ChildScope private constructor()

abstract class GrandchildScope private constructor()

interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Cache<T>(val id: Int)

class Counter {
  var count = 0
}

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")
}

@DependencyGraph(AppScope::class, bindingContainers = [Encoders::class])
interface AppGraph {
  val intCache: Cache<Int>
  val counter: Counter
  val childFactory: ChildGraph.Factory

  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()

  @Provides
  @SingleIn(AppScope::class)
  fun <T> cache(counter: Counter): Cache<T> = Cache(++counter.count)
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val grandchildFactory: GrandchildGraph.Factory

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@GraphExtension(GrandchildScope::class)
interface GrandchildGraph {
  val encoder: Encoder<List<List<String>>>
  val stringCache: Cache<String>
  val intCache: Cache<Int>

  @GraphExtension.Factory
  interface Factory {
    fun create(): GrandchildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val first = parent.childFactory.create().grandchildFactory.create()
  val second = parent.childFactory.create().grandchildFactory.create()
  assertEquals("list(list(string))", first.encoder.name)
  assertSame(first.stringCache, second.stringCache)
  assertSame(parent.intCache, first.intCache)
  assertSame(parent.intCache, second.intCache)
  assertNotSame<Any>(first.stringCache, first.intCache)
  assertEquals(2, parent.counter.count)
  return "OK"
}
