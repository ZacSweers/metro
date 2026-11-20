// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@Suppress("DEPRECATION")
class ExampleClass <!SUGGEST_CLASS_INJECTION!>@AssistedInject<!> constructor(
  @Assisted val count: String,
) {
  @AssistedFactory
  interface Factory {
    fun <!ASSISTED_INJECTION_ERROR!>create<!>(@Assisted notcount: Int): ExampleClass
  }
}
