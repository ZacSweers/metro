// RUN_PIPELINE_TILL: FIR2IR

@Inject class Label

class Target {
  @Inject lateinit var label: Label
}

// The member injector needs an existing Target. The graph has no way to create one.
@DependencyGraph
interface AppGraph {
  val <!MISSING_BINDING!>target<!>: Target
}
