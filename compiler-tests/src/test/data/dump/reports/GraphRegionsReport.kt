// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json
// CHECK_REPORTS: graph-metadata/graph-AppGraph-Impl-ChildGraphImpl.json
// CHECK_REPORTS: graph-metadata/graph-AppGraph-Impl-ChildGraphImpl-GrandchildGraphImpl.json

interface ExternalDependencies {
  val endpoint: String
}

@SingleIn(AppScope::class) @Inject class Cache

@Inject class Value(val cache: Cache, val endpoint: String)

@GraphExtension
interface GrandchildGraph {
  val value: Value
}

@GraphExtension
interface ChildGraph {
  val value: Value
  val grandchild: GrandchildGraph

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val value: Value
  val childFactory: ChildGraph.Factory

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes dependencies: ExternalDependencies): AppGraph
  }
}
