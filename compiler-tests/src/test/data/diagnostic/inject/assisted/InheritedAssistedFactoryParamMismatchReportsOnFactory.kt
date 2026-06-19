// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
)

interface BaseFactory {
  fun create(count: Int, message: String): ExampleClass
}

@AssistedFactory
interface <!ASSISTED_INJECTION_ERROR!>ExampleClassFactory<!> : BaseFactory
