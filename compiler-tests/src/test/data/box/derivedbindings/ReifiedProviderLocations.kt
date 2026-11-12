// ENABLE_DERIVED_BINDINGS

// Reified providers resolve from an object container, a companion object, a final container class
// that the graph holds as an instance, and a final member of an abstract class graph.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object ObjectEncoders {
  @Provides
  inline fun <reified T> list(): Encoder<List<T>> = NamedEncoder("list(${T::class.simpleName})")
}

@BindingContainer
class InstanceEncoders(val prefix: String) {
  @Provides
  inline fun <reified T> set(): Encoder<Set<T>> =
    NamedEncoder("$prefix:set(${T::class.simpleName})")
}

@BindingContainer
class CompanionEncoders {
  companion object {
    @Provides
    inline fun <reified T> array(): Encoder<Array<T>> =
      NamedEncoder("array(${T::class.simpleName})")
  }
}

@DependencyGraph(bindingContainers = [ObjectEncoders::class, CompanionEncoders::class])
interface AppGraph {
  val list: Encoder<List<String>>
  val set: Encoder<Set<Int>>
  val setProvider: Provider<Encoder<Set<Long>>>
  val array: Encoder<Array<String>>
  val pair: Encoder<Pair<String, Int>>

  companion object {
    @Provides
    inline fun <reified A, reified B> pair(): Encoder<Pair<A, B>> =
      NamedEncoder("pair(${A::class.simpleName}, ${B::class.simpleName})")
  }

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes encoders: InstanceEncoders): AppGraph
  }
}

@DependencyGraph(bindingContainers = [ObjectEncoders::class])
abstract class ClassGraph {
  abstract val map: Encoder<Map<String, Int>>
  abstract val list: Encoder<List<Int>>

  @Provides
  inline fun <reified K, reified V> map(): Encoder<Map<K, V>> =
    NamedEncoder("map(${K::class.simpleName}, ${V::class.simpleName})")
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(InstanceEncoders("json"))
  assertEquals("list(String)", graph.list.name)
  assertEquals("json:set(Int)", graph.set.name)
  assertEquals("json:set(Long)", graph.setProvider().name)
  assertEquals("array(String)", graph.array.name)
  assertEquals("pair(String, Int)", graph.pair.name)
  val classGraph = createGraph<ClassGraph>()
  assertEquals("map(String, Int)", classGraph.map.name)
  assertEquals("list(Int)", classGraph.list.name)
  return "OK"
}
