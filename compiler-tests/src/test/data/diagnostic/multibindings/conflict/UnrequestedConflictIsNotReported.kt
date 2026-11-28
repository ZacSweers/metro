// RUN_PIPELINE_TILL: BACKEND

// Like duplicate bindings, a conflict for a key that nothing requests isn't reported by default.
@DependencyGraph
interface <!SUSPICIOUS_UNUSED_MULTIBINDING!>ExampleGraph<!> {
  val int: Int

  @Provides fun provideInt(): Int = 1

  @Provides fun provideStrings(): Set<String> = setOf("whole")

  @Provides @IntoSet fun provideChild(): String = "child"
}
