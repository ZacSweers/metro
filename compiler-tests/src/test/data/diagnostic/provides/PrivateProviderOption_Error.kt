// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_PROVIDER_SEVERITY: ERROR
// DISABLE_TRANSFORM_PROVIDERS_TO_PRIVATE

interface ExampleGraph {
  @Provides val provideCharSequence: String get() = "Hello"
  @Provides fun <!PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_ERROR!>provideString<!>(): String = "Hello"
}
