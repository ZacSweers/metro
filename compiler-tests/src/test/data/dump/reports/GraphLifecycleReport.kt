// RUN_PIPELINE_TILL: BACKEND
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json

class Input

@DependencyGraph interface AppGraph : CloseableGraph {
  @OnGraphInit fun start() {}
  @OnGraphClose fun stop(input: Input) {}
  @DependencyGraph.Factory fun interface Factory { fun create(@Provides input: Input): AppGraph }
}
