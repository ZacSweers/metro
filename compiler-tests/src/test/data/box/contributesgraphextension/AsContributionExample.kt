// https://github.com/ZacSweers/metro/issues/824
// MODULE: lib
abstract class ParentScope
abstract class ChildScope

@SingleIn(ChildScope::class)
@ContributesGraphExtension(ChildScope::class, isExtendable = true)
interface ChildGraph {

  /** Factory for creating the child graph. */
  @ContributesGraphExtension.Factory(ParentScope::class)
  interface Factory {
    /** Create a child graph with the parent graph as a dependency. */
    fun create(): ChildGraph
  }
}

// MODULE: main(lib)
@SingleIn(ParentScope::class)
@DependencyGraph(ParentScope::class, isExtendable = true)
interface ParentGraph {
}

fun box(): String {
  val parentGraph = createGraph<ParentGraph>()
  val childGraph = parentGraph.asContribution<ChildGraph.Factory>().create()
  return "OK"
}