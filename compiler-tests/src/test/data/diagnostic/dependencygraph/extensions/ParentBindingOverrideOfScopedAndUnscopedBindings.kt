// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// Overrides of a scoped and an unscoped parent binding are both reported. The child inherits the
// unscoped one directly and reads the scoped one from the parent graph.

class Foo(val value: String)

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val string: String
  val foo: Foo

  @Provides fun provideString(): String = "parent"

  @SingleIn(AppScope::class) @Provides fun provideFoo(): Foo = Foo("parent")

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val string: String
  val foo: Foo

  @Provides fun <!PARENT_BINDING_OVERRIDE_ERROR!>provideString<!>(): String = "child"

  @Provides fun <!PARENT_BINDING_OVERRIDE_ERROR!>provideFoo<!>(): Foo = Foo("child")

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
