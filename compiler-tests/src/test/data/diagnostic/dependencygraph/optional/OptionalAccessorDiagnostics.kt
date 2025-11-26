// RENDER_DIAGNOSTICS_FULL_TEXT
@DependencyGraph
interface AppGraph {
  // Missing body
  @OptionalBinding
  val <!DEPENDENCY_GRAPH_ERROR!>int<!>: Int

  // Missing body
  @OptionalBinding
  fun <!DEPENDENCY_GRAPH_ERROR!>long<!>(): Long
}

interface Base {
  @OptionalBinding
  fun string(): String
}

@DependencyGraph
abstract class AppGraphClass : Base {
  // Missing annotation
  override abstract fun <!DEPENDENCY_GRAPH_ERROR!>string<!>(): String

  // Non-open
  @OptionalBinding
  val <!DEPENDENCY_GRAPH_ERROR!>int<!>: Int get() = 3

  // ok
  @OptionalBinding
  open val char: Char get() = 'c'

  // Non-open
  @OptionalBinding
  fun <!DEPENDENCY_GRAPH_ERROR!>long<!>(): Long = 3L

  // ok
  @OptionalBinding
  open fun bool(): Boolean = true
}
