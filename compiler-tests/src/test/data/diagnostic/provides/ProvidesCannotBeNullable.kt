// RENDER_DIAGNOSTICS_FULL_TEXT

abstract class ExampleGraph {
  @Provides
  fun <!PROVIDES_ERROR!>provideString<!>(): String? = null
  @Provides val <!PROVIDES_ERROR!>provideLong<!>: Long? = null

  companion object {
    @Provides
    fun <!PROVIDES_ERROR!>provideInt<!>(): Int? = null
  }
}
