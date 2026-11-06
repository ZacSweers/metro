// FORCE_PROVIDER_GETTERS: true
// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 2
// MULTIBINDING_GETTER_THRESHOLD: 1

var constructions = 0

@Inject class Dependency

@Inject class Foo(val dependency: Dependency) {
  init {
    constructions++
  }
}

@Inject class Consumer(val foo: Provider<Foo>)

@SingleIn(AppScope::class)
@Inject class ScopedFoo(val dependency: Dependency)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val foo: Provider<Foo>
  val direct: Consumer
  val consumer: Provider<Consumer>
  val scoped: Provider<ScopedFoo>
  val set: Provider<Set<Foo>>
  val map: Provider<Map<String, Foo>>
  val providerMap: Provider<Map<String, Provider<Foo>>>
  val child: ChildGraph

  @Provides @IntoSet fun firstSet(foo: Foo): Foo = foo
  @Provides @IntoSet fun secondSet(foo: Foo): Foo = foo
  @Provides @IntoMap @StringKey("first") fun firstMap(foo: Foo): Foo = foo
  @Provides @IntoMap @StringKey("second") fun secondMap(foo: Foo): Foo = foo
}

@GraphExtension
interface ChildGraph {
  val foo: Provider<Foo>
  val set: Provider<Set<Foo>>
  val scoped: Provider<ScopedFoo>
}

fun box(): String {
  constructions = 0
  val graph = createGraph<AppGraph>()
  assertNotSame(graph.foo, graph.foo)
  assertNotSame(graph.consumer, graph.consumer)
  assertNotSame(graph.direct.foo, graph.direct.foo)
  assertNotSame(graph.consumer().foo, graph.consumer().foo)
  assertNotSame(graph.set, graph.set)
  assertNotSame(graph.map, graph.map)
  assertNotSame(graph.providerMap, graph.providerMap)
  assertEquals(0, constructions)

  val provider = graph.foo
  assertNotSame(provider(), provider())
  assertEquals(2, constructions)
  assertEquals(2, graph.set().size)
  assertEquals(setOf("first", "second"), graph.map().keys)
  assertEquals(6, constructions)
  val providers = graph.providerMap()
  assertEquals(6, constructions)
  // Map entries keep their providers across reads.
  assertSame(providers.getValue("first"), graph.providerMap().getValue("first"))
  assertNotSame(providers.getValue("first")(), providers.getValue("first")())

  assertSame(graph.scoped, graph.scoped)
  assertSame(graph.scoped(), graph.scoped())
  assertNotSame(graph.scoped(), createGraph<AppGraph>().scoped())

  val child = graph.child
  assertNotSame(child.foo, child.foo)
  assertNotSame(child.set, child.set)
  assertNotSame(child.foo(), graph.foo())
  assertSame(graph.scoped(), child.scoped())
  assertEquals(2, child.set().size)
  return "OK"
}
