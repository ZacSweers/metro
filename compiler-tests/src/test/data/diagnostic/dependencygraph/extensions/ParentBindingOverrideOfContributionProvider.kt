// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// GENERATE_CONTRIBUTION_PROVIDERS: true
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// A @ContributesBinding class overrides the parent's binding through its generated contribution
// provider. The override is reported on the class.

abstract class ChildScope

interface Greeter

object ParentGreeter : Greeter

@ContributesBinding(ChildScope::class) @Inject class <!PARENT_BINDING_OVERRIDE_ERROR!>ChildGreeter<!> : Greeter

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val greeter: Greeter

  @Provides fun provideGreeter(): Greeter = ParentGreeter

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val greeter: Greeter

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
