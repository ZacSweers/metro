@SingleIn(AppScope::class)
@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @Provides @SingleIn(AppScope::class) fun provideExampleClass(): ExampleClass = ExampleClass()
}

@Inject
class ExampleClass

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration, propertyDeclaration */
