// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_SCOPED_PROVIDER_SEVERITY: WARN

abstract class ExampleGraph {
  @Provides @SingleIn(AppScope::class) val provideInt: Int = 0
  @Provides @SingleIn(AppScope::class) val provideCharSequence: String get() = "Hello"
  @Provides @SingleIn(AppScope::class) fun <!SCOPED_PROVIDES_SHOULD_BE_PRIVATE_WARNING!>provideString<!>(): String = "Hello"
}
