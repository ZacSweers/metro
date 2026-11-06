// ENABLE_DAGGER_INTEROP

// The factory for a default-valued parameter takes Metro's Provider, so the graph creates no
// javax.inject.Provider conversion for it.

@Inject class Foo

@Inject class DefaultConsumer(
  val foo: javax.inject.Provider<Foo> = error("The graph should supply Foo"),
)

@DependencyGraph
interface AppGraph {
  val consumer: Provider<DefaultConsumer>
}
