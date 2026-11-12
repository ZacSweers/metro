// ENABLE_DERIVED_BINDINGS

// A graph extension resolves keys from a parent's unscoped and parent-scoped reified providers. The
// scoped instance lives in the parent and is shared.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Cache<T>(val name: String, val id: Int)

class Counter {
  var count = 0
}

@BindingContainer
object ParentBindings {
  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()

  @Provides
  inline fun <reified T> encoder(counter: Counter): Encoder<T> =
    NamedEncoder("${T::class.simpleName}#${++counter.count}")

  @Provides
  @SingleIn(AppScope::class)
  inline fun <reified T> cache(counter: Counter): Cache<T> =
    Cache(T::class.simpleName!!, ++counter.count)
}

@DependencyGraph(AppScope::class, bindingContainers = [ParentBindings::class])
interface AppGraph {
  val counter: Counter
  val stringCache: Cache<String>
  val childFactory: ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val stringEncoder: Encoder<String>
  val intEncoderProvider: Provider<Encoder<Int>>
  val stringCache: Cache<String>
  val intCache: Cache<Int>
  val intCacheProvider: Provider<Cache<Int>>

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val first = parent.childFactory.create()
  val second = parent.childFactory.create()
  assertEquals(0, parent.counter.count)
  assertEquals("String#1", first.stringEncoder.name)
  assertEquals("String#2", second.stringEncoder.name)
  val intEncoders = first.intEncoderProvider
  assertEquals("Int#3", intEncoders().name)
  assertEquals("Int#4", intEncoders().name)
  val strings = first.stringCache
  assertEquals("String", strings.name)
  assertSame(strings, second.stringCache)
  assertSame(strings, parent.stringCache)
  val ints = second.intCache
  assertEquals("Int", ints.name)
  assertSame(ints, first.intCache)
  assertSame(ints, first.intCacheProvider())
  assertNotEquals(strings.id, ints.id)
  assertEquals(6, parent.counter.count)
  return "OK"
}
