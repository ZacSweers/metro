// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// A parameter-less @Binds declares Foo's own @Inject binding in the child. Foo's scope belongs to
// ParentGraph, so Metro reports the scope. The declaration replaces nothing, so it isn't also
// reported as an override.

@SingleIn(AppScope::class) @Inject class Foo

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val foo: Foo

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface <!INCOMPATIBLE_SCOPE!>ChildGraph<!> {
  val foo: Foo

  @Binds fun bindFoo(): Foo

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
