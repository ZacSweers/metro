// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
) {
  @AssistedFactory
  interface <!FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION!>Factory<!>
}
