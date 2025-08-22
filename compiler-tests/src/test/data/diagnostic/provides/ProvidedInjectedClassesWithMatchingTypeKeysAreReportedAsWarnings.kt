// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @Provides fun <!PROVIDES_WARNING!>provideExampleClass<!>(): ExampleClass = ExampleClass()
}

@Inject
class ExampleClass

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, propertyDeclaration */
