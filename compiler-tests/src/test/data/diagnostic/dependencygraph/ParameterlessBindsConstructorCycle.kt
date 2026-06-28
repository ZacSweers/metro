// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>AppGraph<!> {
  val target: Target

  @Binds fun bindTarget(): Target
}

@Inject class Target(val dependency: Dependency)

@Inject class Dependency(val target: Target)
