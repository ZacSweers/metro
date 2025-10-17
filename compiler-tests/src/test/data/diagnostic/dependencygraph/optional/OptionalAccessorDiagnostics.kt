// RENDER_DIAGNOSTICS_FULL_TEXT
@DependencyGraph
interface AppGraph {
  // Missing body
  @OptionalDependency
  val <!DEPENDENCY_GRAPH_ERROR!>int<!>: Int

  // Missing body
  @OptionalDependency
  fun <!DEPENDENCY_GRAPH_ERROR!>long<!>(): Long
}

interface Base {
  @OptionalDependency
  fun string(): String
}

@DependencyGraph
abstract class AppGraphClass : Base {
  // Missing annotation
  override abstract fun <!DEPENDENCY_GRAPH_ERROR!>string<!>(): String

  // Non-open
  @OptionalDependency
  val <!DEPENDENCY_GRAPH_ERROR!>int<!>: Int get() = 3

  // ok
  @OptionalDependency
  open val char: Char get() = 'c'

  // Non-open
  @OptionalDependency
  fun <!DEPENDENCY_GRAPH_ERROR!>long<!>(): Long = 3L

  // ok
  @OptionalDependency
  open fun bool(): Boolean = true
}
