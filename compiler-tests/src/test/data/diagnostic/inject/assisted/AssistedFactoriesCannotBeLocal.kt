// RENDER_DIAGNOSTICS_FULL_TEXT
class ExampleClass

fun example() {
  @AssistedFactory
  abstract class <!METRO_DECLARATION_ERROR!>ExampleClassFactory<!> {
    abstract fun create(count: Int): ExampleClass
  }
}
