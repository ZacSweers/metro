// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

@DependencyGraph
interface ExampleGraph : TextProvider {

  val value: String

  @Provides
  override fun provideValue(): String = "Hello, overridden world!"
}

interface TextProvider {
  fun provideValue(): String = "Hello, world!"
}
