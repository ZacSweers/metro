// ENABLE_DAGGER_INTEROP
// FORCE_PROVIDER_GETTERS: true
// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 1
// STATEMENTS_PER_INIT_FUN: 2
// MULTIBINDING_GETTER_THRESHOLD: 1

@Inject class Dependency
@Inject class Foo(val dependency: Dependency)
@Inject class Consumer(val foo: javax.inject.Provider<Foo>)

@SingleIn(AppScope::class)
@Inject class ScopedFoo(val dependency: Dependency)

@Inject class CycleFoo(val bar: CycleBar)
@Inject class CycleBar(val foo: javax.inject.Provider<CycleFoo>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val consumer: Provider<Consumer>
  val foo: javax.inject.Provider<Foo>
  val scoped: javax.inject.Provider<ScopedFoo>
  val cycle: javax.inject.Provider<CycleFoo>
  val set: Provider<Set<Foo>>
  val map: Provider<Map<String, Foo>>

  @Provides @IntoSet fun firstSet(foo: Foo): Foo = foo
  @Provides @IntoSet fun secondSet(foo: Foo): Foo = foo
  @Provides @IntoMap @StringKey("first") fun firstMap(foo: Foo): Foo = foo
  @Provides @IntoMap @StringKey("second") fun secondMap(foo: Foo): Foo = foo
}
