// RENDER_DIAGNOSTICS_FULL_TEXT

interface ExampleGraph {
  @Provides
  fun <!METRO_TYPE_PARAMETERS_ERROR!><T><!> provideString(): String = "Hello"

  companion object {
    @Provides
    fun <!METRO_TYPE_PARAMETERS_ERROR!><T><!> provideInt(): Int = 0
  }
}
