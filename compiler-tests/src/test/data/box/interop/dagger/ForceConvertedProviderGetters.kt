// ENABLE_DAGGER_INTEROP
// FORCE_PROVIDER_GETTERS: true
// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 1
// STATEMENTS_PER_INIT_FUN: 2

var constructions = 0

@Inject class Dependency
@Inject class Foo(val dependency: Dependency) {
  init {
    constructions++
  }
}

@Inject class Consumer(val foo: javax.inject.Provider<Foo>)
@Inject class DefaultConsumer(
  val foo: javax.inject.Provider<Foo> = error("The graph should supply Foo"),
)

@SingleIn(AppScope::class)
@Inject class ScopedFoo(val dependency: Dependency)

@Inject class CycleFoo(val bar: CycleBar)
@Inject class CycleBar(val foo: javax.inject.Provider<CycleFoo>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val foo: javax.inject.Provider<Foo>
  val direct: Consumer
  val consumer: Provider<Consumer>
  val defaultConsumer: Provider<DefaultConsumer>
  val scoped: javax.inject.Provider<ScopedFoo>
  val jakarta: jakarta.inject.Provider<ScopedFoo>
  val cycle: javax.inject.Provider<CycleFoo>
}

fun box(): String {
  constructions = 0
  val graph = createGraph<AppGraph>()
  assertNotSame(graph.foo, graph.foo)
  assertNotSame(graph.direct.foo, graph.direct.foo)
  assertNotSame(graph.consumer().foo, graph.consumer().foo)
  assertEquals(0, constructions)
  val factory = graph.consumer
  assertSame(factory().foo, factory().foo)
  assertNotSame(factory().foo.get(), factory().foo.get())
  assertNotSame(graph.defaultConsumer().foo.get(), graph.defaultConsumer().foo.get())

  val factoryClass = Consumer::class.java.declaredClasses.single { it.simpleName == "MetroFactory" }
  val create = factoryClass.getMethod("create", javax.inject.Provider::class.java)
  val provider = graph.foo
  val created = create.invoke(null, provider) as Provider<*>
  assertSame(provider, (created() as Consumer).foo)

  assertNotSame(graph.scoped, graph.scoped)
  assertNotSame(graph.jakarta, graph.jakarta)
  assertSame(graph.scoped.get(), graph.jakarta.get())
  assertNotSame(graph.scoped.get(), createGraph<AppGraph>().scoped.get())

  val cycle = graph.cycle.get()
  val next = cycle.bar.foo.get()
  assertNotSame(cycle, next)
  assertNotSame(next, next.bar.foo.get())
  return "OK"
}
