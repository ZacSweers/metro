// ENABLE_DAGGER_INTEROP
// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 1
// STATEMENTS_PER_INIT_FUN: 2

@Inject class Dependency
@Inject class Foo(val dependency: Dependency)
@Inject class Consumer(val foo: javax.inject.Provider<Foo>)

@DependencyGraph
interface ConstructorGraph {
  val consumer: Provider<Consumer>
}

@DependencyGraph
interface AccessorGraph {
  val foo: javax.inject.Provider<Foo>
  val sameFoo: javax.inject.Provider<Foo>
}

@DependencyGraph
interface MixedProviderGraph {
  val foo: javax.inject.Provider<Foo>
  val metro: Provider<Foo>
}

@SingleIn(AppScope::class)
@Inject class ScopedFoo(val dependency: Dependency)

@DependencyGraph(AppScope::class)
interface ScopedGraph {
  val foo: javax.inject.Provider<ScopedFoo>
  val metro: Provider<ScopedFoo>
}

@Inject class CycleFoo(val bar: CycleBar)
@Inject class CycleBar(val foo: javax.inject.Provider<CycleFoo>)

@DependencyGraph
interface CycleGraph {
  val foo: javax.inject.Provider<CycleFoo>
}
