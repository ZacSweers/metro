// RENDER_DIAGNOSTICS_FULL_TEXT

abstract class ExampleGraph {
  @Provides abstract val <!PROVIDES_ERROR!>provideInt<!>: Int
  @Provides abstract fun <!PROVIDES_ERROR!>provideString<!>(): String
}
