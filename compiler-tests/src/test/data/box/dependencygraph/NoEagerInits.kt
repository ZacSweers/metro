// Tests that creating a dependency graph doesn't trigger eager initialization
// All provider functions throw exceptions - they should never be called during graph creation

// Constructor-injected class with scope
@Inject
@SingleIn(AppScope::class)
class ScopedService(val value: String)

// Constructor-injected classes that create a cycle
@Inject
@SingleIn(AppScope::class)
class CycleA(val b: Provider<CycleB>)

@Inject
@SingleIn(AppScope::class)
class CycleB(val a: Provider<CycleA>)

// Constructor-injected class that depends on multibindings
@Inject
@SingleIn(AppScope::class)
class Consumer(
  val cycleA: Provider<CycleA>,
  val cycleB: Provider<CycleB>,
  val scopedService: Provider<ScopedService>,
  val set: Provider<Set<String>>,
  val map: Provider<Map<String, Int>>,
  val providerMap: Provider<Map<Int, Provider<String>>>,
)

@DependencyGraph(AppScope::class)
interface NoEagerInitGraph {
  val consumer: Consumer

  // Misc accessors, never called
  val cycleA: CycleA
  val cycleB: CycleB
  val scopedService: ScopedService
  val set: Set<String>
  val map: Map<String, Int>
  val providerMap: Map<Int, Provider<String>>

  @Provides
  fun provideString(): String {
    error("Should never be called")
  }

  @Provides
  @IntoSet
  fun provideInt1(): Int {
    error("Should never be called")
  }

  @Provides
  @IntoSet
  fun provideInt2(): Int {
    error("Should never be called")
  }

  @Provides
  @ElementsIntoSet
  fun provideInts(): Set<Int> {
    error("Should never be called")
  }

  @Provides
  @IntoSet
  fun provideString1(): String {
    error("Should never be called")
  }

  @Provides
  @IntoSet
  fun provideString2(): String {
    error("Should never be called")
  }

  @Provides
  @IntoMap
  @StringKey("key1")
  fun provideMapInt1(): Int {
    error("Should never be called")
  }

  @Provides
  @IntoMap
  @StringKey("key2")
  fun provideMapInt2(): Int {
    error("Should never be called")
  }

  @Provides
  @IntoMap
  @IntKey(1)
  fun provideMapString1(): String {
    error("Should never be called")
  }

  @Provides
  @IntoMap
  @IntKey(2)
  fun provideMapString2(): String {
    error("Should never be called")
  }
}

fun box(): String {
  // Creating the graph should not trigger any of the throwing provider functions
  val graph = createGraph<NoEagerInitGraph>()
  assertNotNull(graph)

  // Verify cycle types can be accessed (but don't call their providers)
  assertNotNull(graph.cycleA)
  assertNotNull(graph.cycleB)

  // Verify consumer can be created
  assertNotNull(graph.consumer)

  return "OK"
}