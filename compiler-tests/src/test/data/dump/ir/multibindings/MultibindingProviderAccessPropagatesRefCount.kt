// Tests that accessing multibindings via Provider properly propagates factory refcounts
// to the multibinding sources AND their transitive dependencies.
//
// Scenarios covered:
// 1. Provider<Set<A>> - sources should be marked, and their deps (X) should be marked
// 2. Provider<Map<K, V>> - sources should be marked, and their deps should be marked
// 3. Map<K, Provider<V>> - sources should be marked (Provider values)
//
// Key: if A depends on X, and A is marked as a factory access, X should also be marked
// when A is processed (since A is in a factory path with factoryRefCount > 0).

@DependencyGraph
interface TestGraph {
  // Accessors that trigger Provider access on multibindings
  val setConsumer: SetConsumer
  val mapConsumer: MapConsumer
  val providerMapConsumer: ProviderMapConsumer

  @Binds fun XImpl.bindX(): X

  // Set<A> multibinding
  @Binds @IntoSet fun A.bindA(): A

  // Map<Int, B> multibinding
  @Binds @IntoMap @IntKey(1) fun B.bindB(): B

  // Map<Int, Provider<C>> multibinding (Provider values)
  @Binds @IntoMap @IntKey(1) fun C.bindC(): C
}

// X is a transitive dependency - used by sources of all multibindings
// If sources are properly marked, X should have factoryRefCount = 3 (one from each source)
// and get a backing field
// interface + impl to ensure we follow aliases too
interface X
@Inject
class XImpl : X
@Inject
class A(val x: X)
@Inject
class B(val x: X)
@Inject
class C(val x: X)

// Each consumer accesses a multibinding via Provider
@Inject class SetConsumer(val set: Provider<Set<A>>)
@Inject class MapConsumer(val map: Provider<Map<Int, B>>)
@Inject class ProviderMapConsumer(val map: Map<Int, Provider<C>>)