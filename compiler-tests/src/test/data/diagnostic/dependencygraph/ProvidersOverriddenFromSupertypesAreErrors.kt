// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph : TextProvider {

  val value: String

  override fun <!PROVIDER_OVERRIDES!>provideValue<!>(): String = "Hello, overridden world!"
}

interface TextProvider {
  @Provides
  fun provideValue(): String = "Hello, world!"
}
