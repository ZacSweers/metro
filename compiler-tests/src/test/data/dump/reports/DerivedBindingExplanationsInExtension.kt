// RUN_PIPELINE_TILL: BACKEND
// ENABLE_DERIVED_BINDINGS
// Generated child accessors have different source offsets across Kotlin versions.
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json
// CHECK_REPORTS: graph-metadata/graph-AppGraph-Impl-ChildGraphImpl.json

interface Encoder<T>

class NamedEncoder<T>(val name: String) : Encoder<T>

class Token

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun <T> anything(): Encoder<T> = NamedEncoder("anything")

  // Only the child graph requests this, so the child redirects it to the parent that owns the scope.
  @Provides
  @SingleIn(AppScope::class)
  fun <K, V> map(keys: Encoder<K>, values: Encoder<V>): Encoder<Map<K, V>> = NamedEncoder("map")
}

@DependencyGraph(AppScope::class, bindingContainers = [Encoders::class])
interface AppGraph {
  val child: ChildGraph
}

@GraphExtension
interface ChildGraph {
  val tokensByName: Encoder<Map<String, Token>>
}
