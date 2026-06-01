// MERGED_SUPERTYPE_CHUNK_SIZE: 2
// KEYS_PER_GRAPH_SHARD: 4
// ENABLE_GRAPH_SHARDING: true
// STATEMENTS_PER_INIT_FUN: 2
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json
// CHECK_REPORTS: provider-factories/StatsModule.ProvideGMetroFactory.kt

@ContributesTo(AppScope::class)
interface ContributedA {
  val a: A
}

@ContributesTo(AppScope::class)
interface ContributedB {
  val b: B
}

@ContributesTo(AppScope::class)
interface ContributedC {
  val c: C
}

@SingleIn(AppScope::class) @Inject class A

@SingleIn(AppScope::class) @Inject class B(val a: A)

@SingleIn(AppScope::class) @Inject class C(val b: B)

@SingleIn(AppScope::class) @Inject class D(val c: C)

@SingleIn(AppScope::class) @Inject class E(val d: D)

@SingleIn(AppScope::class) @Inject class F(val e: E)

class G(val f: F)

@BindingContainer
@ContributesTo(AppScope::class)
object StatsModule {
  @Provides fun provideG(f: F): G = G(f)
}

@Suppress("OPT_IN_USAGE")
@MergeContributionsInIr
@DependencyGraph(AppScope::class)
interface AppGraph {
  val f: F
  val g: G
}
