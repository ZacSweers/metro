// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 5

// This test verifies that a warning is emitted when the user configures
// keysPerGraphShard but the graph is too small for sharding to be applied.

@Inject @SingleIn(AppScope::class) class Binding1(val binding2: Binding2)

@Inject class Binding2

@DependencyGraph(AppScope::class)
interface <!METRO_WARNING!>AppGraph<!> {
  val binding1: Binding1
}
