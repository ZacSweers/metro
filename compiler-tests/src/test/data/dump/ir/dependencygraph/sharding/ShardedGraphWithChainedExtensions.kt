// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies that graph sharding works with chained (nested) graph extensions.
 *
 * Graph structure: Parent (AppGraph) → Child (ChildGraph) → Grandchild (GrandchildGraph)
 * Expected shards: Only parent graph sharded, child and grandchild extensions NOT sharded
 * - AppGraph sharded: AppService1, AppService2, AppService3
 * - ChildGraph: ChildService1, ChildService2, ChildService3 (accesses AppGraph)
 * - GrandchildGraph: GrandchildService1, GrandchildService2 (accesses both parent and child)
 *
 * Validation: Generated IR shows parent sharded, nested extensions not sharded, multi-level access works
 */

abstract class ChildScope private constructor()
abstract class GrandchildScope private constructor()

// Parent graph bindings - should create 2 shards
@SingleIn(AppScope::class) @Inject class AppService1

@SingleIn(AppScope::class) @Inject class AppService2

@SingleIn(AppScope::class) @Inject class AppService3

// Child extension bindings - should NOT be sharded
@SingleIn(ChildScope::class) @Inject class ChildService1(val app1: AppService1)

@SingleIn(ChildScope::class) @Inject class ChildService2(val child1: ChildService1)

@SingleIn(ChildScope::class) @Inject class ChildService3(val child2: ChildService2)

// Grandchild extension bindings - should NOT be sharded
@SingleIn(GrandchildScope::class) @Inject class GrandchildService1(val app2: AppService2, val child3: ChildService3)

@SingleIn(GrandchildScope::class) @Inject class GrandchildService2(val gc1: GrandchildService1)

@DependencyGraph(scope = AppScope::class)
interface AppGraph {
  val appService1: AppService1
  val appService2: AppService2
  val appService3: AppService3
  val childFactory: ChildGraph.Factory
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val childService3: ChildService3
  val grandchildFactory: GrandchildGraph.Factory

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  fun interface Factory {
    fun createChild(): ChildGraph
  }
}

@GraphExtension(GrandchildScope::class)
interface GrandchildGraph {
  val grandchildService2: GrandchildService2

  @GraphExtension.Factory
  @ContributesTo(ChildScope::class)
  fun interface Factory {
    fun createGrandchild(): GrandchildGraph
  }
}
