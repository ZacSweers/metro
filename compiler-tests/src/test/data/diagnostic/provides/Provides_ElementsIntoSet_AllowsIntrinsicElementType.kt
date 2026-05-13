// RENDER_DIAGNOSTICS_FULL_TEXT

// @ElementsIntoSet declarations may return Set<Provider<T>> — the return type itself is
// Set<...>, not an intrinsic, so the check naturally passes. Also negative coverage for
// user-defined subtypes of Provider, which should not be caught by the identity-based check.

class MyProvider : Provider<String> { override fun invoke(): String = "" }

@DependencyGraph
interface ExampleGraph {
  val strings: Set<Provider<String>>
  val myProvider: MyProvider

  @Provides @ElementsIntoSet fun provideStrings(): Set<Provider<String>> =
    setOf(provider { "a" }, provider { "b" })

  // User-defined subclass of Provider is allowed (identity check, no supertype walk).
  @Provides fun provideMyProvider(): MyProvider = MyProvider()
}
