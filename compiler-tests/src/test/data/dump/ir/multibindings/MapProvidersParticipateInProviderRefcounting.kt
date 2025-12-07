@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3
  @Binds @IntoMap @IntKey(3) fun Int.bindInt(): Int

  // Int is used as a provider in both this accessor and the map, so we should refcount it
  val int: Provider<Int>
  val ints: Map<Int, Provider<Int>>
}