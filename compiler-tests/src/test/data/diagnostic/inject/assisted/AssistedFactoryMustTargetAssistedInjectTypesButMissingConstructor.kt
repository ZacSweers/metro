// RENDER_DIAGNOSTICS_FULL_TEXT
class ExampleClass

@AssistedFactory
fun interface ExampleClassFactory {
  fun create(count: Int): <!ASSISTED_INJECTION_ERROR!>ExampleClass<!>
}
