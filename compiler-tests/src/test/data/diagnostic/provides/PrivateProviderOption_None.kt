// PUBLIC_PROVIDER_SEVERITY: NONE

interface ExampleGraph {
  @Provides val provideCharSequence: String get() = "Hello"
  @Provides fun provideString(): String = "Hello"
}
