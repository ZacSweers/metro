// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @Provides fun <!REDUNDANT_PROVIDES!>provideExampleClass<!>(): ExampleClass = ExampleClass()
}

@Inject
class ExampleClass
