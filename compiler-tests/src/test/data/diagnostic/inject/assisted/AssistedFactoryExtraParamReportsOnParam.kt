// RENDER_DIAGNOSTICS_FULL_TEXT

@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
)

@AssistedFactory
fun interface ExampleClassFactory {
  fun create(count: Int, <!ASSISTED_INJECTION_ERROR!>message<!>: String): ExampleClass
}
