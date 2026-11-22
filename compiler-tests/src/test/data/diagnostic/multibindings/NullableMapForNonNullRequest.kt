// RUN_PIPELINE_TILL: FIR2IR

@DependencyGraph
interface Graph {
  val <!MISSING_BINDING!>values<!>: Map<String, String>
  @Provides fun supply(): Map<String, String>? = null
}
