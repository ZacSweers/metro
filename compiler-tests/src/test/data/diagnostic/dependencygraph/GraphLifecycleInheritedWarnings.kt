// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// METRO_JVM_ONLY

// MODULE: library
// FILE: Hooks.kt

@Inject class First
@Inject class Second

interface Hooks {
  @OnGraphClose fun stop(first: First, second: Second) {}
}

// MODULE: main(library)
// FILE: AppGraph.kt

<!CLOSE_ONLY_BINDING, UNSCOPED_LIFECYCLE_PARAMETER!>@DependencyGraph interface AppGraph : CloseableGraph, Hooks<!>
