// ENABLE_DAGGER_INTEROP
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

@Inject class DirectConsumer(val foo: javax.inject.Provider<Foo>)
@Inject class FactoryConsumer(val foo: javax.inject.Provider<Foo>)
@SingleIn(AppScope::class)
@Inject class ScopedConsumer(val foo: javax.inject.Provider<Foo>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val direct: DirectConsumer
  val factory: Provider<FactoryConsumer>
  val scoped: ScopedConsumer
  val foo: javax.inject.Provider<Foo>
}

@DependencyGraph
interface FactoryOnlyGraph {
  val consumer: Provider<FactoryConsumer>
}

@Inject class DefaultConsumer(
  val foo: javax.inject.Provider<Foo> = error("The graph should supply Foo"),
)

@DependencyGraph
interface DefaultFactoryGraph {
  val consumer: Provider<DefaultConsumer>
}

@SingleIn(AppScope::class)
@Inject class SharedFoo(val dependency: Dependency)

@DependencyGraph(AppScope::class)
interface SharedConversionsGraph {
  val javax: javax.inject.Provider<SharedFoo>
  val jakarta: jakarta.inject.Provider<SharedFoo>
}

@Inject class CycleFoo(val bar: CycleBar)
@Inject class CycleBar(val foo: javax.inject.Provider<CycleFoo>)

@DependencyGraph
interface CycleGraph {
  val foo: javax.inject.Provider<CycleFoo>
}

fun box(): String {
  constructions = 0
  val graph = createGraph<AppGraph>()
  val provider = graph.direct.foo
  assertSame(provider, graph.direct.foo)
  assertSame(provider, graph.factory().foo)
  assertSame(provider, graph.factory().foo)
  assertSame(provider, graph.scoped.foo)
  assertSame(provider, graph.foo)
  assertEquals(0, constructions)
  val first = provider.get()
  val second = provider.get()
  assertNotSame(first, second)
  assertNotSame(first.dependency, second.dependency)
  assertEquals(2, constructions)
  assertNotSame(provider, createGraph<AppGraph>().direct.foo)

  val factoryClass = FactoryConsumer::class.java.declaredClasses.single { it.simpleName == "MetroFactory" }
  val create = factoryClass.getMethod("create", javax.inject.Provider::class.java)
  val createdFactory = create.invoke(null, provider) as Provider<*>
  assertSame(provider, (createdFactory() as FactoryConsumer).foo)
  val providerFields = factoryClass.declaredFields.filterNot {
    java.lang.reflect.Modifier.isStatic(it.modifiers)
  }
  assertEquals(listOf(javax.inject.Provider::class.java), providerFields.map { it.type })

  val factoryOnly = createGraph<FactoryOnlyGraph>().consumer
  assertSame(factoryOnly().foo, factoryOnly().foo)
  assertNotSame(factoryOnly().foo.get(), factoryOnly().foo.get())

  val defaultFactory = createGraph<DefaultFactoryGraph>().consumer
  assertNotSame(defaultFactory().foo.get(), defaultFactory().foo.get())

  val shared = createGraph<SharedConversionsGraph>()
  assertSame(shared.javax, shared.javax)
  assertSame(shared.jakarta, shared.jakarta)
  assertSame(shared.javax.get(), shared.jakarta.get())
  assertNotSame(shared.javax.get(), createGraph<SharedConversionsGraph>().javax.get())

  val cycle = createGraph<CycleGraph>()
  val cycleProvider = cycle.foo
  assertSame(cycleProvider, cycleProvider.get().bar.foo)
  assertNotSame(cycleProvider.get(), cycleProvider.get())
  return "OK"
}
