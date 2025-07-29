// RENDER_DIAGNOSTICS_FULL_TEXT

interface ExampleGraph {
  @Provides val <!PROVIDES_ERROR!>provideInt<!>: Int
  @Provides fun <!PROVIDES_ERROR!>provideString<!>(): String
}

/* GENERATED_FIR_TAGS: functionDeclaration, interfaceDeclaration, propertyDeclaration */
