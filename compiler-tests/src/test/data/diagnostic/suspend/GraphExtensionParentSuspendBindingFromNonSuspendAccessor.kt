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
  val <!METRO_ERROR!>value<!>: String

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
