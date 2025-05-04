// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_PROVIDER_SEVERITY: WARN
// DISABLE_TRANSFORM_PROVIDERS_TO_PRIVATE

interface ExampleGraph {
  @Provides val provideCharSequence: String get() = "Hello"
  @Provides fun <!PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING!>provideString<!>(): String = "Hello"
}
