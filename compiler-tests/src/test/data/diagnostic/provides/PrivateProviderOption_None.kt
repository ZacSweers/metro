// PUBLIC_PROVIDER_SEVERITY: NONE
// DISABLE_TRANSFORM_PROVIDERS_TO_PRIVATE

interface ExampleGraph {
  @Provides val provideCharSequence: String get() = "Hello"
  @Provides fun provideString(): String = "Hello"
}

/* GENERATED_FIR_TAGS: functionDeclaration, getter, interfaceDeclaration, propertyDeclaration, stringLiteral */
