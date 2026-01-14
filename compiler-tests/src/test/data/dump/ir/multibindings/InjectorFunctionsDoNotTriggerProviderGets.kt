class Target {
  @Inject lateinit var strings: Map<Int, Provider<String>>
}

interface Bindings {
  @IntKey(1) @Provides @IntoMap fun provideString(): String = "hello"
}

@DependencyGraph
interface TestGraph1 : Bindings {
  // Scalar injector only, triggers scalar getter
  fun inject(target: Target)
}

@DependencyGraph
interface TestGraph2 : Bindings {
  // Mixed scalar/provider roots, trigger provider getter
  fun inject(target: Target)

  val strings: Provider<Map<Int, Provider<String>>>
}

@DependencyGraph
interface TestGraph3 : Bindings {
  // Both scalar roots, triggers scalar getter
  fun inject(target: Target)

  val strings: Map<Int, Provider<String>>
}

@DependencyGraph
interface TestGraph4 : Bindings {
  // Only a Provider accessor, triggers Provider getter
  val strings: Provider<Map<Int, Provider<String>>>
}

@DependencyGraph
interface TestGraph5 : Bindings {
  // Only a scalar accessor, triggers scalar getter
  val strings: Map<Int, Provider<String>>
}
