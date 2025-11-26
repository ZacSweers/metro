// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
) {
  @AssistedFactory
  <!METRO_DECLARATION_VISIBILITY_ERROR!>private<!> fun interface Factory {
    fun create(count: Int): ExampleClass
  }
}
