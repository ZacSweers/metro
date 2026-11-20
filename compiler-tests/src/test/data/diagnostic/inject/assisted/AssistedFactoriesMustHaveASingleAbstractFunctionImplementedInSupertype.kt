// RENDER_DIAGNOSTICS_FULL_TEXT

package test

class ExampleClass <!SUGGEST_CLASS_INJECTION!>@AssistedInject<!> constructor(
  @Assisted val count: Int,
) {
  interface GrandParentFactory {
    fun create(count: Int): ExampleClass
  }
  interface ParentFactory : GrandParentFactory {
    override fun create(count: Int): ExampleClass {
      throw NotImplementedError()
    }
  }
  @AssistedFactory
  interface <!FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION!>Factory<!> : ParentFactory
}
