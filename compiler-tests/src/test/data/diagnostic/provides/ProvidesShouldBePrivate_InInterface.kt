// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_PROVIDER_SEVERITY: WARN

interface ExampleGraph {
  @Provides val provideCharSequence: String get() = "Hello"
  @Provides fun <!PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING!>provideString<!>(): String = "Hello"
}
