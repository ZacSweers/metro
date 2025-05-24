// RENDER_DIAGNOSTICS_FULL_TEXT

abstract class ExampleGraph {
  @Provides val String.<!PROVIDES_ERROR!>provideCharSequence<!>: CharSequence get() = "hello"
  @Provides fun Int.<!PROVIDES_ERROR!>provideNumber<!>(): Number = 3
}
