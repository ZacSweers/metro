// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@file:OptIn(ExperimentalMetroApi::class)

@DependencyGraph
interface <!MISSING_RUNTIME_COROUTINES!>ExampleGraph<!> : SuspendCloseableGraph
