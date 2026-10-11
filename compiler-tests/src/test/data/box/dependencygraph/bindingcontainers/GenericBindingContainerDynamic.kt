// Generic binding container with createDynamicGraph
@DependencyGraph
interface AppGraph {
  val value: Long

  @Provides fun defaultValue(): Long = 0L
}

@BindingContainer
class DynamicValueBindings<T : Number>(private val value: T) {
  @Provides fun provideLong(): Long = value.toLong()
}

@DependencyGraph
interface FactoryGraph {
  val value: Long

  @Provides fun defaultValue(): Long = 0L

  @DependencyGraph.Factory
  interface Factory {
    fun create(): FactoryGraph
  }
}

@DependencyGraph
interface DeferredGraph {
  val valueProvider: Provider<Long>
  val lazyValue: Lazy<Long>

  @Provides fun defaultValue(): Long = 0L
}

// These type names share a hash, so the generated-name allocator must handle the collision too.
class Aa

class BB

@BindingContainer
class TypedBindings<T>(private val value: Long) {
  @Provides fun provideLong(): Long = value
}

fun box(): String {
  val graph = createDynamicGraph<AppGraph>(DynamicValueBindings(42))
  val repeated = createDynamicGraph<AppGraph>(DynamicValueBindings(43))
  val longGraph = createDynamicGraph<AppGraph>(DynamicValueBindings(44L))
  assertEquals(42L, graph.value)
  assertEquals(43L, repeated.value)
  assertEquals(44L, longGraph.value)
  assertEquals(graph::class, repeated::class)
  assertNotEquals(graph::class, longGraph::class)

  val intFactory = createDynamicGraphFactory<FactoryGraph.Factory>(DynamicValueBindings(45))
  val repeatedFactory = createDynamicGraphFactory<FactoryGraph.Factory>(DynamicValueBindings(46))
  val longFactory = createDynamicGraphFactory<FactoryGraph.Factory>(DynamicValueBindings(47L))
  assertEquals(45L, intFactory.create().value)
  assertEquals(46L, repeatedFactory.create().value)
  assertEquals(47L, longFactory.create().value)
  assertEquals(intFactory::class, repeatedFactory::class)
  assertNotEquals(intFactory::class, longFactory::class)

  val aaGraph = createDynamicGraph<AppGraph>(TypedBindings<Aa>(48L))
  val bbGraph = createDynamicGraph<AppGraph>(TypedBindings<BB>(49L))
  assertEquals(48L, aaGraph.value)
  assertEquals(49L, bbGraph.value)
  assertNotEquals(aaGraph::class, bbGraph::class)

  // Deferred access calls the generated factory with this container's concrete type arguments.
  val intDeferred = createDynamicGraph<DeferredGraph>(DynamicValueBindings(50))
  val longDeferred = createDynamicGraph<DeferredGraph>(DynamicValueBindings(51L))
  assertEquals(50L, intDeferred.valueProvider())
  assertEquals(50L, intDeferred.lazyValue.value)
  assertEquals(51L, longDeferred.valueProvider())
  assertEquals(51L, longDeferred.lazyValue.value)
  return "OK"
}
