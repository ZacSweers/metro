// RUN_PIPELINE_TILL: FIR2IR

// MODULE: library
// DISABLE_METRO
// FILE: Hooks.kt

interface Hooks {
  @OnGraphClose @OnGraphInit fun run() {}
}

// MODULE: main(library)
// FILE: AppGraph.kt

@DependencyGraph
interface <!DEPENDENCY_GRAPH_ERROR!>AppGraph<!> : CloseableGraph, Hooks
