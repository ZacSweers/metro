// A single-element Set multibinding accessed through a Provider should emit
// `SetFactory.singleton(provider)` rather than building a Builder.

@DependencyGraph
interface AppGraph {
  @Provides fun provideOnly(): String = "only"

  @Binds @IntoSet fun String.bindOnly(): String

  val tags: () -> Set<String>
}
