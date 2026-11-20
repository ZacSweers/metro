// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>ExampleGraph<!> {

  val value: String

  @Provides
  fun provideString(int: Int): String {
      return "Value: " + int
  }

  @Provides
  fun provideInt(double: Double): Int {
      return double.toInt()
  }

  @Provides
  fun provideDouble(string: String): Double {
      return string.length.toDouble()
  }
}
