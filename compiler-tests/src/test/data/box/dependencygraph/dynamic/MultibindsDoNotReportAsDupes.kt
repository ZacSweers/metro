// Regression test to ensure we only process upstream binding containers once
// https://github.com/ZacSweers/metro/issues/2005
// MODULE: lib
interface Listener

@DependencyGraph(scope = AppScope::class, bindingContainers = [AppModule::class])
interface AppGraph {
  @DependencyGraph.Factory
  interface Factory {
    fun create(): AppGraph
  }

  @Multibinds(allowEmpty = true) fun listeners(): Set<Listener>
}

@BindingContainer
interface AppModule {
  companion object {
    @Provides @IntoSet fun provideListener(): Listener = object : Listener {}
  }
}

// MODULE: main(lib)
@BindingContainer object TestOverrideModule

fun box(): String {
  val graph = createDynamicGraphFactory<AppGraph.Factory>(TestOverrideModule).create()
  assertNotNull(graph.listeners())
  return "OK"
}
