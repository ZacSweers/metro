// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
package test

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>CharSequenceGraph<!> {

  fun value(): CharSequence

  @Provides
  fun provideValue(string: String): CharSequence = string

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes stringGraph: StringGraph): CharSequenceGraph
  }
}

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>StringGraph<!> {

  val string: String

  @Provides
  fun provideValue(charSequence: CharSequence): String = charSequence.toString()

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes charSequenceGraph: CharSequenceGraph): StringGraph
  }
}
