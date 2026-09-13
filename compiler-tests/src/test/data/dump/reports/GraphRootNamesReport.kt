// RUN_PIPELINE_TILL: BACKEND
// Generated injectors have different source offsets across Kotlin versions.
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json

// MODULE: api
@Inject class Value

class Target {
  @Inject lateinit var value: Value
}

interface BinaryParent {
  val inherited: Value
  fun inheritedValue(): Value
  fun inject(target: Target)
}

// MODULE: main(api)
interface SourceParent {
  val sourceValue: Value
}

@DependencyGraph
interface AppGraph : BinaryParent, SourceParent {
  val value: Value
  fun value(): Value
}
