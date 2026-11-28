// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A child's @Includes accessor and an unscoped parent @Provides of the same type are duplicate bindings.

interface StringDep {
  val string: String
}

@DependencyGraph(AppScope::class)
interface <!DUPLICATE_BINDING!>AppGraph<!> {
  val childGraphFactory: ChildGraph.Factory

  @Provides fun provideString(): String = "parent"
}

@GraphExtension
interface ChildGraph {
  val string: String

  @GraphExtension.Factory
  interface Factory {
    fun create(@Includes dep: StringDep): ChildGraph
  }
}
