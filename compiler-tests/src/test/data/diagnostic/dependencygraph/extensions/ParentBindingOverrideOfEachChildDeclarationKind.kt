// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// Overrides from a @Binds, a graph extension factory @Provides input, and a
// @ContributesBinding class are each reported on the child's declaration.

abstract class ChildScope

interface Greeter

object ParentGreeter : Greeter

@ContributesBinding(ChildScope::class) @Inject class <!PARENT_BINDING_OVERRIDE_ERROR!>ChildGreeter<!> : Greeter

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val chars: CharSequence
  val int: Int
  val greeter: Greeter

  @Provides fun provideChars(): CharSequence = "parent"

  @Provides fun provideInt(): Int = 1

  @Provides fun provideGreeter(): Greeter = ParentGreeter

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val chars: CharSequence
  val int: Int
  val greeter: Greeter

  @Provides fun provideString(): String = "child"

  @Binds <!PARENT_BINDING_OVERRIDE_ERROR!>val String.bind: CharSequence<!>

  @GraphExtension.Factory
  interface Factory {
    fun create(<!PARENT_BINDING_OVERRIDE_ERROR!>@Provides int: Int<!>): ChildGraph
  }
}
