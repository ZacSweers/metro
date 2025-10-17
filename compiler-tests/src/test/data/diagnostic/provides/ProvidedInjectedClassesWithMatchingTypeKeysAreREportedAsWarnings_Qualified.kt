// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @Provides @Named("hello") fun <!REDUNDANT_PROVIDES!>provideExampleClass<!>(): ExampleClass = ExampleClass()
}

@Named("hello")
@Inject
class ExampleClass
