// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

@DependencyGraph
interface ExampleGraph {
  val message: String

  @Provides private fun provideInt(): Int = 3

  @Provides
  private fun provideMessage(
    intValue: Int,
    input: CharSequence = "Not found: " + intValue,
  ): String = input.toString()
}
