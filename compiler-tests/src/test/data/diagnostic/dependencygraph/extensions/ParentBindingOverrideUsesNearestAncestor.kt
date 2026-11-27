// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// GrandparentGraph provides String and ChildGraph's factory input overrides it. GrandchildGraph
// overrides both. Its override is reported once and names the nearest ancestor, ChildGraph.

@DependencyGraph
interface GrandparentGraph {
  val string: String

  @Provides fun provideString(): String = "grandparent"

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val string: String

  fun grandchildGraphFactory(): GrandchildGraph.Factory

  @GraphExtension.Factory
  interface Factory {
    fun create(<!PARENT_BINDING_OVERRIDE_ERROR!>@Provides string: String<!>): ChildGraph
  }
}

@GraphExtension
interface GrandchildGraph {
  val string: String

  @Provides fun <!PARENT_BINDING_OVERRIDE_ERROR!>provideString<!>(): String = "grandchild"

  @GraphExtension.Factory
  interface Factory {
    fun create(): GrandchildGraph
  }
}
