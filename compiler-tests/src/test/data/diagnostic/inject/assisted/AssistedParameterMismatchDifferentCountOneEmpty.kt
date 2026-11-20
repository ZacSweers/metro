// RENDER_DIAGNOSTICS_FULL_TEXT

package test

class ExampleClass <!SUGGEST_CLASS_INJECTION!>@AssistedInject<!> constructor(
  @Assisted val count: Int,
) {
  @AssistedFactory
  interface Factory {
    fun <!ASSISTED_INJECTION_ERROR!>create<!>(): ExampleClass
  }
}
