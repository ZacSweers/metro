// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
) {
  @AssistedFactory
  annotation class <!METRO_DECLARATION_ERROR!>Factory<!>
}
