// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @Provides @Named("hello") fun <!PROVIDES_WARNING!>provideExampleClass<!>(): ExampleClass = ExampleClass()
}

@Named("hello")
@Inject
class ExampleClass

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, propertyDeclaration, stringLiteral */
