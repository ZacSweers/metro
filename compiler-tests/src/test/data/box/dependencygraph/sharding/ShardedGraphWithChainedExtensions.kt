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
 * Validation: Multi-level extension hierarchy works with sharded parent graph
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

fun box(): String {
  val appGraph = createGraph<AppGraph>()

  // Verify parent graph (sharded)
  if (appGraph.appService1 == null) return "FAIL: appService1 null"
  if (appGraph.appService3 == null) return "FAIL: appService3 null"

  // Verify child extension (not sharded) can access parent
  val childGraph = appGraph.childFactory.createChild()
  if (childGraph.childService3.child2.child1.app1 == null) return "FAIL: child chain broken"
  if (childGraph.childService3.child2.child1.app1 !== appGraph.appService1) {
    return "FAIL: child doesn't share parent instance"
  }

  // Verify grandchild extension (not sharded) can access parent and grandparent
  val grandchildGraph = childGraph.grandchildFactory.createGrandchild()
  if (grandchildGraph.grandchildService2.gc1.app2 == null) return "FAIL: grandchild parent access broken"
  if (grandchildGraph.grandchildService2.gc1.child3 == null) return "FAIL: grandchild child access broken"
  if (grandchildGraph.grandchildService2.gc1.app2 !== appGraph.appService2) {
    return "FAIL: grandchild doesn't share grandparent instance"
  }
  if (grandchildGraph.grandchildService2.gc1.child3 !== childGraph.childService3) {
    return "FAIL: grandchild doesn't share child instance"
  }

  return "OK"
}
