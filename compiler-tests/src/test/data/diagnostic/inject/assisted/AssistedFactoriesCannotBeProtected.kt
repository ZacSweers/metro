// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
) {
  @AssistedFactory
  <!METRO_DECLARATION_VISIBILITY_ERROR!>protected<!> fun interface Factory {
    fun create(count: Int): ExampleClass
  }
}
