// Tests that multibinding source bindings get proper refcounting when the
// multibinding itself is accessed via factory path (has refCount > 1).
//
// Bug scenario: We skip processing multibindings in BindingPropertyCollector,
// so even if a multibinding has refCount > 1:
// 1. The multibinding doesn't get a backing field
// 2. Its source bindings don't get marked as provider accesses
// 3. Dependencies of source bindings don't get proper refcounts
//
// In this test:
// - Set<Int> (multibinding) is accessed via Provider by SetConsumer
// - SetConsumer is accessed via Provider twice (refCount = 2)
// - SetConsumer uses factory path, so Set<Int> gets marked (refCount = 1)
// - If we had another consumer, Set<Int> would have refCount = 2
//
// Expected:
// - If Set<Int> has refCount > 1 and gets a property, its source bindings
//   (provideInt1, provideInt2) should be marked as provider accesses
// - Since both use factory path, Base should have refCount = 2 and get a field
//
// Current bug: Base gets instantiated multiple times instead of being cached.

@DependencyGraph
interface TestGraph {
  val entry: Entry

  // Base is used by both IntoSet providers
  @Provides fun provideBase(): Base = Base()

  @Provides @IntoSet fun provideInt1(base: Base): Int = 1
  @Provides @IntoSet fun provideInt2(base: Base): Int = 2
}

class Base

// SetConsumer depends on Set<Int> directly
@Inject class SetConsumer(val set: Set<Int>)

// Two places access SetConsumer via Provider -> SetConsumer refCount = 2
// SetConsumer uses factory path -> Set<Int> gets markProviderAccess
// If there are 2+ such consumers, Set<Int> would have refCount > 1
@Inject class ConsumerA(val consumer: Provider<SetConsumer>)
@Inject class ConsumerB(val consumer: Provider<SetConsumer>)

@Inject class Entry(val a: ConsumerA, val b: ConsumerB)