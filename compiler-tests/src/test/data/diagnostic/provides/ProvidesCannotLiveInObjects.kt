// RENDER_DIAGNOSTICS_FULL_TEXT

object InvalidContainer {
  @Provides fun <!PROVIDES_ERROR!>provideString<!>(): String = "Hello"
}
