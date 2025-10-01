// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
) {
  @AssistedFactory
  object <!METRO_DECLARATION_ERROR!>Factory<!> {
    fun create(count: Int): ExampleClass {
      throw NotImplementedError()
    }
  }
}
