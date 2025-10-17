// RENDER_DIAGNOSTICS_FULL_TEXT

interface Example {
  @Provides
  fun provideString(<!PROVIDES_ERROR!>@Assisted<!> int: Int): String = "Hello, assisted parameters"
}
