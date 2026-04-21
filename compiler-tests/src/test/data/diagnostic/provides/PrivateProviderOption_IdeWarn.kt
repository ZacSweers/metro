// PUBLIC_SCOPED_PROVIDER_SEVERITY: IDE_WARN

// IDE_WARN should produce no diagnostics in a CLI compilation (the diagnostic test harness always
// runs in CLI mode). If the scoped public provider check fires here, the severity plumbing is
// wrong — IDE-only severities must resolve to NONE outside of IDE sessions.
interface ExampleGraph {
  @Provides @SingleIn(AppScope::class) val provideCharSequence: String get() = "Hello"
  @Provides @SingleIn(AppScope::class) fun provideString(): String = "Hello"
}
