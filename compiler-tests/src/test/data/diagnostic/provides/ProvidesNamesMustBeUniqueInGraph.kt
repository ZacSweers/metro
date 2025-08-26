// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  val number: Number
  val int: Int
  
  @Provides fun <!BINDING_CONTAINER_ERROR!>provideNumber<!>(): Number = 3
  @Provides fun <!BINDING_CONTAINER_ERROR!>provideNumber<!>(string: String): Int = string.length
}
