// Tests that multibinding source bindings get proper refcounting when
// the multibinding is accessed via factory path (not directly as Provider<Set<...>>).
//
// Scenario:
// - SetConsumerA and SetConsumerB both depend on Set<Int> (the multibinding)
// - Both SetConsumerA and SetConsumerB are accessed via Provider twice
// - This gives them refCount = 2, so they use factory path
// - Since they use factory path, their dependency (Set<Int>) should be marked via markProviderAccess
// - Set<Int> gets refCount = 2
// - When processing Set<Int>, we should check refCount > 1 and potentially mark its sources
//
// The bug: we `continue` for multibindings before checking refCount,
// so even if Set<Int> has refCount > 1, we don't process it properly.

@DependencyGraph
interface TestGraph {
  val entry: Entry

  // Common dependency used by multiple IntoSet contributors
  @Provides fun provideBase(): Base = Base()

  @Provides @IntoSet fun provideInt1(base: Base): Int = 1
  @Provides @IntoSet fun provideInt2(base: Base): Int = 2
}

class Base

// Two different classes both depend on Set<Int>
@Inject class SetConsumerA(val set: Set<Int>)
@Inject class SetConsumerB(val set: Set<Int>)

// Each consumer is accessed via Provider twice, giving them refCount = 2
@Inject class WrapperA1(val consumer: Provider<SetConsumerA>)
@Inject class WrapperA2(val consumer: Provider<SetConsumerA>)
@Inject class WrapperB1(val consumer: Provider<SetConsumerB>)
@Inject class WrapperB2(val consumer: Provider<SetConsumerB>)

@Inject class Entry(val a1: WrapperA1, val a2: WrapperA2, val b1: WrapperB1, val b2: WrapperB2)