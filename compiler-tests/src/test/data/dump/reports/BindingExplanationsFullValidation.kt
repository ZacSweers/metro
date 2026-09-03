// RUN_PIPELINE_TILL: BACKEND
// ENABLE_FULL_BINDING_GRAPH_VALIDATION
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json

class RequestedValue

class UnrequestedValue

@DependencyGraph
interface AppGraph {
  val requestedValue: RequestedValue

  @Provides fun requestedValue(): RequestedValue = RequestedValue()

  @Provides fun unrequestedValue(): UnrequestedValue = UnrequestedValue()
}
