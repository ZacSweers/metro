// RENDER_DIAGNOSTICS_FULL_TEXT

abstract class ExampleGraph {
  @Provides
  fun <<!DERIVED_BINDINGS_NOT_ENABLED!>T<!>> provideString(): String = "Hello"

  companion object {
    @Provides
    fun <<!DERIVED_BINDINGS_NOT_ENABLED!>T<!>> provideInt(): Int = 0
  }
}
