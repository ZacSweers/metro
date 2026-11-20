// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  <!SCOPED_GRAPH_ACCESSOR!>@get:SingleIn(AppScope::class)<!>
  val value: String
}
