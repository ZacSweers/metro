// ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE
@GraphExtension(String::class)
interface ChildGraph {
  @GraphExtension.Factory @ContributesTo(AppScope::class)
  fun interface Factory {
    fun create(): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph
