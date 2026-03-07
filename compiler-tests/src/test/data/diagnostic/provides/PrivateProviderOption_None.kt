// PUBLIC_SCOPED_PROVIDER_SEVERITY: NONE

interface ExampleGraph {
  @Provides @SingleIn(AppScope::class) val provideCharSequence: String get() = "Hello"
  @Provides @SingleIn(AppScope::class) fun provideString(): String = "Hello"
}
