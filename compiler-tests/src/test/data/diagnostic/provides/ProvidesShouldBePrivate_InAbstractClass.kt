// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_PROVIDER_SEVERITY: WARN

abstract class ExampleGraph {
  @Provides val provideInt: Int = 0
  @Provides val provideCharSequence: String get() = "Hello"
  @Provides fun <!PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING!>provideString<!>(): String = "Hello"
}
