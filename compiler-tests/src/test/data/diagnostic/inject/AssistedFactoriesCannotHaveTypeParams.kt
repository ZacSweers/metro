// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@AssistedInject
class ExampleClass<T> {
  @AssistedFactory
  interface Factory {
    fun <T> <!ASSISTED_INJECTION_ERROR!>create<!>(): ExampleClass<T>
  }
}
