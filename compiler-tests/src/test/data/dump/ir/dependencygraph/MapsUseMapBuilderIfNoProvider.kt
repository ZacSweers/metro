@DependencyGraph
interface AppGraph {
  @Multibinds
  val ints: Map<Int, Int>

  val intsWithProviders: Map<Int, Provider<Int>>

  @Provides @IntoMap @IntKey(3) fun provideInt(): Int = 3
}