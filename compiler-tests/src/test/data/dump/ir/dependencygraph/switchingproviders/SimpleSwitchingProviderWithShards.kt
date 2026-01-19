// KEYS_PER_GRAPH_SHARD: 1
// ENABLE_GRAPH_SHARDING: true
// ENABLE_SWITCHING_PROVIDERS: true

@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String
  val scopedClass: ScopedClass

  val int1: Provider<Int>
  val int2: Provider<Int>

  @SingleIn(AppScope::class)
  @Provides
  fun provideString(): String = "Hello"

  @Provides
  fun provideReusedInt(): Int = 3
}

@Inject
@SingleIn(AppScope::class)
class ScopedClass
