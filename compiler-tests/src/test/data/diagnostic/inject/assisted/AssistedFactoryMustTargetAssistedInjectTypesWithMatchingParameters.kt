// RENDER_DIAGNOSTICS_FULL_TEXT
@AssistedInject
class <!ASSISTED_INJECTION_ERROR!>ExampleClass<!>(
  val count: Int,
  val message: String,
)

@AssistedFactory
fun interface ExampleClassFactory {
  fun create(count: Int): ExampleClass
}
