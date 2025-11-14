// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

// This test verifies that:
// 1. Parent graph gets sharded when it has many bindings
// 2. Graph extensions (subcomponents) are NOT sharded regardless of binding count
// 3. Multiple extensions can coexist with a sharded parent

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
