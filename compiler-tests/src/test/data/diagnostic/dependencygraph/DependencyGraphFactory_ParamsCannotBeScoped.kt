// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Provides <!SCOPED_GRAPH_FACTORY_PARAMETER!>@SingleIn(AppScope::class)<!> string: String,
      @Includes <!SCOPED_GRAPH_FACTORY_PARAMETER!>@SingleIn(AppScope::class)<!> included: IncludedBindings,
    ): ExampleGraph
  }
}

@BindingContainer
object IncludedBindings
