// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
) {
  @AssistedFactory
  interface Factory {
    fun <!FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION!>create<!>(count: Int): ExampleClass
    fun <!FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION!>create2<!>(count: Int): ExampleClass
  }
}
