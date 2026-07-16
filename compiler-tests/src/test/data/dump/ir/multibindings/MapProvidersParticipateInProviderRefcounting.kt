// ENABLE_SUSPEND_PROVIDERS
@file:Suppress("OPT_IN_USAGE")

@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3
  @Binds @IntoMap @IntKey(3) fun Int.bindInt(): Int

  @Provides fun provideSharedDependency(): SharedDependency = SharedDependency()

  @Provides @IntoMap @StringKey("first")
  fun provideFirst(sharedDependency: SharedDependency): String = "first"

  @Provides @IntoMap @StringKey("second")
  fun provideSecond(sharedDependency: SharedDependency): String = "second"

  // Int is used as a provider in both this accessor and the map, so we should refcount it
  val int: () -> Int
  val ints: Map<Int, () -> Int>

  // A suspend-provider-valued map creates factories for its contributions. Both contribution
  // factories need SharedDependency, so its provider should be cached and shared between them.
  val strings: Map<String, SuspendProvider<String>>
}

class SharedDependency
