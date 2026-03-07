// RENDER_DIAGNOSTICS_FULL_TEXT
// ASSISTED_IDENTIFIER_SEVERITY: ERROR
@AssistedInject
data class ExampleClass(
  @Assisted(<!ASSISTED_INJECTION_ERROR!>"customId"<!>) val size: Int,
) {
  @AssistedFactory
  interface Factory {
    fun create(@Assisted(<!ASSISTED_INJECTION_ERROR!>"customId"<!>) size: Int): ExampleClass
  }
}
