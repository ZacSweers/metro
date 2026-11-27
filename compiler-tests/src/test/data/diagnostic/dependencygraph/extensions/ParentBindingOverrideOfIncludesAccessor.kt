// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// Overriding a binding that the parent gets from an @Includes accessor is reported.

interface StringDep {
  val string: String
}

@DependencyGraph
interface ParentGraph {
  val string: String

  fun childGraphFactory(): ChildGraph.Factory

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes dep: StringDep): ParentGraph
  }
}

@GraphExtension
interface ChildGraph {
  val string: String

  @Provides fun <!PARENT_BINDING_OVERRIDE_ERROR!>provideString<!>(): String = "child"

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
