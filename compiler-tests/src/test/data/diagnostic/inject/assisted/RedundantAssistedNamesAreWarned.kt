// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
data class ExampleClass(
  @Assisted(<!ASSISTED_INJECTION_WARNING!>"size"<!>) val size: Int,
  @Assisted(<!ASSISTED_INJECTION_WARNING!>"invalidCount"<!>) val invalidCount: Int,
) {
  @AssistedFactory
  interface Factory {
    fun create(@Assisted(<!ASSISTED_INJECTION_WARNING!>"size"<!>) size: Int, @Assisted(<!ASSISTED_INJECTION_WARNING!>"invalidCount"<!>) invalidCount: Int): ExampleClass
  }
}
