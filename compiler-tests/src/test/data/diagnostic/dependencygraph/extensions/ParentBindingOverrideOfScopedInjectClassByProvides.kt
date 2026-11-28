// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// A @Provides for a scoped @Inject class gets the FIR scope warning in any graph. In a child of the
// graph that owns the scope, it's also an override.

@SingleIn(AppScope::class) @Inject open class Foo

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val foo: Foo

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val foo: Foo

  @Provides fun <!CONFLICTING_PROVIDES_SCOPE, PARENT_BINDING_OVERRIDE_ERROR!>provideFoo<!>(): Foo = object : Foo() {}

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
