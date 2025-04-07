// RENDER_DIAGNOSTICS_FULL_TEXT

interface SomeType

fun example() {
  val someType = createGraph<<!CREATE_GRAPH_ERROR!>SomeType<!>>()
  val someType2: SomeType = <!CREATE_GRAPH_ERROR!>createGraph<!>()
}
