// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
  @Assisted val message: String,
)

@AssistedFactory
fun interface ExampleClassFactory {
  fun <!ASSISTED_INJECTION_ERROR!>create<!>(count: Int): ExampleClass
}
