// RENDER_DIAGNOSTICS_FULL_TEXT

@AssistedInject
class Foo(factoryProvider: <!ASSISTED_FACTORIES_CANNOT_BE_LAZY!>Lazy<Factory><!>) {
  @AssistedFactory
  interface Factory {
    fun create(): Foo
  }
}
