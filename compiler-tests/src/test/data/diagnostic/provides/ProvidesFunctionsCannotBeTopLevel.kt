// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
abstract class ExampleGraph {
  abstract val int: Int
}

@Provides fun <!PROVIDES_ERROR!>provideInt<!>(): Int = 0

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, propertyDeclaration */
