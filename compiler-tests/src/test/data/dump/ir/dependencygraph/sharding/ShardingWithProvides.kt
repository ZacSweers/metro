// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies that @Provides bindings work correctly with sharding.
 *
 * Graph structure: @Provides methods providing Service2, used by Service3
 * Expected shards: Mix of @Inject classes and @Provides methods distributed across shards
 *
 * Validation: Generated IR shows @Provides methods accessible across shard boundaries
 */

@SingleIn(AppScope::class) @Inject class Service1

class Service2(val s1: Service1)

@SingleIn(AppScope::class) @Inject class Service3(val s2: Service2)

@BindingContainer
@ContributesTo(AppScope::class)
object AppModule {
  @Provides
  @SingleIn(AppScope::class)
  fun provideService2(s1: Service1): Service2 = Service2(s1)
}

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val service1: Service1
  val service2: Service2
  val service3: Service3
}
