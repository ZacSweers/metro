// ENABLE_DAGGER_INTEROP
// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 1
// STATEMENTS_PER_INIT_FUN: 1
// Repeated accessor calls share the converted provider within each graph.

var barConstructions = 0

@Inject class Bar {
  init {
    barConstructions++
  }
}

@Inject class Foo(val bar: Bar)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val fooProvider: javax.inject.Provider<Foo>
}

@DependencyGraph(AppScope::class)
interface ShardedGraph {
  val fooProvider: javax.inject.Provider<Foo>
  val otherFooProvider: javax.inject.Provider<Foo>
  val barProvider: Provider<Bar>
}

fun box(): String {
  barConstructions = 0
  val graph = createGraph<AppGraph>()
  assertSame(graph.fooProvider, graph.fooProvider)
  assertEquals(0, barConstructions)
  val first = graph.fooProvider.get()
  val second = graph.fooProvider.get()
  assertNotSame(first, second)
  assertNotSame(first.bar, second.bar)
  assertEquals(2, barConstructions)
  assertNotSame(graph.fooProvider, createGraph<AppGraph>().fooProvider)

  val sharded = createGraph<ShardedGraph>()
  assertEquals(2, barConstructions)
  assertSame(sharded.fooProvider, sharded.fooProvider)
  assertSame(sharded.fooProvider, sharded.otherFooProvider)
  assertNotSame(sharded.fooProvider.get(), sharded.fooProvider.get())
  assertEquals(4, barConstructions)
  return "OK"
}
