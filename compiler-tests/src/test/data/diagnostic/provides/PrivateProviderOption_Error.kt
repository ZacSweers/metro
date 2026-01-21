// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_PROVIDER_SEVERITY: ERROR

interface ExampleGraph {
  @Provides val provideCharSequence: String get() = "Hello"
  @Provides fun <!PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_ERROR!>provideString<!>(): String = "Hello"
}
