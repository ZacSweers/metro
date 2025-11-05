// KEYS_PER_GRAPH_SHARD: 3
// ENABLE_GRAPH_SHARDING: true

// This test verifies multibinding sets work across shards

@SingleIn(AppScope::class) @Inject class Service1

@SingleIn(AppScope::class) @Inject class Service2

@SingleIn(AppScope::class) @Inject class Collector(val services: Set<Any>)

@BindingContainer
@ContributesTo(AppScope::class)
object Module1 {
  @Provides
  @IntoSet
  @SingleIn(AppScope::class)
  fun provideService1(s1: Service1): Any = s1
}

@BindingContainer
@ContributesTo(AppScope::class)
object Module2 {
  @Provides
  @IntoSet
  @SingleIn(AppScope::class)
  fun provideService2(s2: Service2): Any = s2
}

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val collector: Collector
}
