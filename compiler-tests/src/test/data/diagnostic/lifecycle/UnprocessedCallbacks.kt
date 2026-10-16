// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
// DISABLE_METRO

// FILE: Callbacks.kt
@file:OptIn(ExperimentalMetroApi::class)

interface AbstractLifecycle {
  @OnGraphInit fun initialize()
}

interface NonUnitLifecycle {
  @OnGraphClose fun cleanup(): String = "invalid"
}

// MODULE: main(lib)

// FILE: main.kt
@file:OptIn(ExperimentalMetroApi::class)

@DependencyGraph
interface <!GRAPH_LIFECYCLE_ERROR!>AbstractGraph<!> : CloseableGraph, AbstractLifecycle

@DependencyGraph
interface <!GRAPH_LIFECYCLE_ERROR!>NonUnitGraph<!> : CloseableGraph, NonUnitLifecycle
