// RENDER_DIAGNOSTICS_FULL_TEXT

abstract class ExampleGraph {
  @Provides
  fun <!PROVIDES_ERROR!>provideString<!>() = "Hello"

  companion object {
    @Provides
    fun <!PROVIDES_ERROR!>provideInt<!>() = 0
  }
}
