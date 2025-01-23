// PUBLIC_PROVIDER_SEVERITY: WARN

interface ExampleGraph {
  @Provides val <!PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING!>provideCharSequence<!>: String get() = "Hello"
  @Provides fun <!PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING!>provideString<!>(): String = "Hello"
}
