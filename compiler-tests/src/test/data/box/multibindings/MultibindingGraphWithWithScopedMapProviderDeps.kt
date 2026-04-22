@SingleIn(AppScope::class)
@DependencyGraph
abstract class MultibindingGraphWithWithScopedMapProviderDeps {
  private var scopedCount = 0
  private var unscopedCount = 0

  abstract val ints: Map<Int, () -> Int>
  abstract val providerInts: () -> Map<Int, () -> Int>
  abstract val lazyInts: Lazy<Map<Int, () -> Int>>

  @Provides @SingleIn(AppScope::class) @IntoMap @IntKey(1) fun provideScopedInt(): Int = scopedCount++

  @Provides @IntoMap @IntKey(2) private fun provideUnscopedInt(): Int = unscopedCount++
}

fun box(): String {
  val graph = createGraph<MultibindingGraphWithWithScopedMapProviderDeps>()

  var unscopedCount = 0
  fun validate(body: () -> Map<Int, () -> Int>) {
    // Scoped int (key = 1) never increments no matter how many times we call the provider
    assertEquals(mapOf(1 to 0, 2 to unscopedCount++), body().mapValues { it.value() })
    assertEquals(mapOf(1 to 0, 2 to unscopedCount++), body().mapValues { it.value() })
    assertEquals(mapOf(1 to 0, 2 to unscopedCount++), body().mapValues { it.value() })
  }

  validate(graph::ints)
  validate { graph.providerInts() }
  validate { graph.lazyInts.value }
  return "OK"
}
