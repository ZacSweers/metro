// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
) {
  @AssistedFactory
  sealed class <!METRO_DECLARATION_ERROR!>Factory<!> {
    abstract fun create(count: Int): ExampleClass
  }
}
