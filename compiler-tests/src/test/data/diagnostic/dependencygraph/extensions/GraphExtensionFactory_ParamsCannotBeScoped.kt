// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph(AppScope::class)
interface ParentGraph : ChildGraph.Factory

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val string: String

  @GraphExtension.Factory
  interface Factory {
    fun create(
      @Provides <!SCOPED_GRAPH_FACTORY_PARAMETER!>@SingleIn(ChildScope::class)<!> string: String,
      @Includes <!SCOPED_GRAPH_FACTORY_PARAMETER!>@SingleIn(ChildScope::class)<!> included: IncludedBindings,
    ): ChildGraph
  }
}

@BindingContainer
object IncludedBindings

abstract class ChildScope private constructor()
