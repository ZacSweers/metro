// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// Overrides from another module's binding containers have no source in this module. Both are
// reported on the child graph.

// MODULE: lib
abstract class ChildScope

@BindingContainer
object IncludedChildBindings {
  @Provides fun provideString(): String = "child"
}

@ContributesTo(ChildScope::class)
@BindingContainer
object ContributedChildBindings {
  @Provides fun provideInt(): Int = 2
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface ParentGraph {
  val string: String
  val int: Int

  @Provides fun provideString(): String = "parent"

  @Provides fun provideInt(): Int = 1

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension(ChildScope::class, bindingContainers = [IncludedChildBindings::class])
interface <!PARENT_BINDING_OVERRIDE_ERROR!>ChildGraph<!> {
  val string: String
  val int: Int

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
