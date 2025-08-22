// RENDER_DIAGNOSTICS_FULL_TEXT

@Inject
class Foo(factoryProvider: <!ASSISTED_FACTORIES_CANNOT_BE_LAZY!>Lazy<Factory><!>) {
  @AssistedFactory
  interface Factory {
    fun create(): Foo
  }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, nestedClass, primaryConstructor */
