// DISABLE_TRANSFORM_PROVIDERS_TO_PRIVATE

interface IntHolder

@Inject
@IntKey(3)
@ContributesIntoMap(AppScope::class)
class IntHolderImpl : IntHolder

@DependencyGraph(AppScope::class)
interface AppGraph {
  val ints: Map<Int, Int>
  val intHolder: Map<Int, IntHolder>

  @Provides @IntKey(1) @IntoMap fun provideInt1(): Int = 1
  @Provides @IntKey(2) @IntoMap fun provideInt2(): Int = 2
}