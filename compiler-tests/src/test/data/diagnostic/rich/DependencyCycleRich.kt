// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// DIAGNOSTICS_RENDER_MODE: RICH
// MAX_COMPILER_VERSION: 2.4.19

// Rich console rendering of a dependency cycle: Unicode loop glyphs + ANSI styling, escaped in
// the golden via AnsiMarkup. Capped to <2.4.20 because the rich golden naming lives in Metro's
// legacy diagnostics handler.

@Inject class A(b: B)

@Inject class B(c: C)

@Inject class C(a: A)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>CycleGraph<!> {
  val a: A
}
