// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies that graph sharding works with multiple graph extensions.
 *
 * Graph structure: Parent graph (AppGraph) with 4 bindings, 2 extension graphs (Feature1, Feature2)
 * Expected shards: Parent graph sharded into 2 shards, extension graphs NOT sharded
 * - AppGraph Shard1: AppService1, AppService2
 * - AppGraph Shard2: AppService3, AppService4
 *
 * Validation: Extensions can access parent graph bindings and create their own dependency chains
 */

abstract class Feature1Scope private constructor()
abstract class Feature2Scope private constructor()

// Parent graph bindings - should create 2 shards
@SingleIn(AppScope::class) @Inject class AppService1

@SingleIn(AppScope::class) @Inject class AppService2

@SingleIn(AppScope::class) @Inject class AppService3

@SingleIn(AppScope::class) @Inject class AppService4

// Feature 1 extension bindings - should NOT be sharded
@SingleIn(Feature1Scope::class) @Inject class Feature1Service1

@SingleIn(Feature1Scope::class) @Inject class Feature1Service2(val f1s1: Feature1Service1)

@SingleIn(Feature1Scope::class) @Inject class Feature1Service3(val f1s2: Feature1Service2)

// Feature 2 extension bindings - should NOT be sharded
@SingleIn(Feature2Scope::class) @Inject class Feature2Service1(val app: AppService1)

@SingleIn(Feature2Scope::class) @Inject class Feature2Service2(val f2s1: Feature2Service1)

@SingleIn(Feature2Scope::class) @Inject class Feature2Service3(val f2s2: Feature2Service2)

@DependencyGraph(scope = AppScope::class)
interface AppGraph {
  val appService1: AppService1
  val appService2: AppService2
  val appService3: AppService3
  val appService4: AppService4
  val feature1Factory: Feature1Graph.Factory
  val feature2Factory: Feature2Graph.Factory
}

@GraphExtension(Feature1Scope::class)
interface Feature1Graph {
  val feature1Service3: Feature1Service3

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  fun interface Factory {
    fun createFeature1(): Feature1Graph
  }
}

@GraphExtension(Feature2Scope::class)
interface Feature2Graph {
  val feature2Service3: Feature2Service3

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  fun interface Factory {
    fun createFeature2(): Feature2Graph
  }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()

  // Verify parent graph works
  if (appGraph.appService1 == null) return "FAIL: appService1 null"
  if (appGraph.appService4 == null) return "FAIL: appService4 null"

  // Verify Feature1 extension works
  val feature1 = appGraph.feature1Factory.createFeature1()
  if (feature1.feature1Service3.f1s2.f1s1 == null) return "FAIL: feature1 chain broken"

  // Verify Feature2 extension works and can access parent
  val feature2 = appGraph.feature2Factory.createFeature2()
  if (feature2.feature2Service3.f2s2.f2s1.app == null) return "FAIL: feature2 chain broken"
  if (feature2.feature2Service3.f2s2.f2s1.app !== appGraph.appService1) {
    return "FAIL: feature2 doesn't share parent instance"
  }

  return "OK"
}
