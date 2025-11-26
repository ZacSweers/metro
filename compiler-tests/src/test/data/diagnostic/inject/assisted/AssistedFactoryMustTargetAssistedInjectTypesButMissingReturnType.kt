// RENDER_DIAGNOSTICS_FULL_TEXT
// Regression test for https://github.com/ZacSweers/metro/issues/364#issuecomment-2841469320

@AssistedFactory
fun interface ExampleClassFactory {
  fun <!ASSISTED_INJECTION_ERROR!>create<!>(count: Int)
}
