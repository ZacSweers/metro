// RUN_PIPELINE_TILL: FIR2IR
// METRO_JVM_ONLY
// DIAGNOSTICS_RENDER_MODE: RICH

// The rich cycle trace includes Unicode loop glyphs.

@Inject class A(b: B)

@Inject class B(c: C)

@Inject class C(a: A)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>CycleGraph<!> {
  val a: A
}
