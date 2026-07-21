// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

abstract class ParentScope private constructor()

@DependencyGraph(scope = ParentScope::class)
interface ParentGraph {
  fun childGraphFactory(): ChildGraph.Factory

  @Provides @SingleIn(ParentScope::class) suspend fun provideValue(): String = "value"
}

@GraphExtension
interface ChildGraph {
  val <!SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR!>value<!>: String

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
