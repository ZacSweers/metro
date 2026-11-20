// RENDER_DIAGNOSTICS_FULL_TEXT

package test

class ExampleClass <!SUGGEST_CLASS_INJECTION!>@AssistedInject<!> constructor(
  @Assisted val count: Int,
) {
  interface BaseFactory {
    fun create(count: Int): ExampleClass
  }
  @AssistedFactory
  interface <!FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION!>Factory<!> : BaseFactory {
    override fun create(count: Int): ExampleClass {
      throw NotImplementedError()
    }
  }
}
