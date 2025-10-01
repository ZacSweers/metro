// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
) {
  @AssistedFactory
  enum class <!METRO_DECLARATION_ERROR!>Factory<!> {
    INSTANCE {
      override fun create(count: Int): ExampleClass {
        throw NotImplementedError()
      }
    };
    abstract fun create(count: Int): ExampleClass
  }
}
