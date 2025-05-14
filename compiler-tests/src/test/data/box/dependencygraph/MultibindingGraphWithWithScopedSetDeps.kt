import kotlin.test.*

@SingleIn(AppScope::class)
@DependencyGraph
abstract class MultibindingGraphWithWithScopedSetDeps {
  private var scopedCount = 0
  private var unscopedCount = 0

  abstract val ints: Set<Int>

  @Provides @SingleIn(AppScope::class) @IntoSet fun provideScopedInt(): Int = scopedCount++

  @Provides @IntoSet private fun provideUnscopedInt(): Int = unscopedCount++
}

fun box(): String {
  val graph = createGraph<MultibindingGraphWithWithScopedSetDeps>()
  assertEquals(setOf(0), graph.ints)
  assertEquals(setOf(0, 1), graph.ints)
  assertEquals(setOf(0, 2), graph.ints)
  return "OK"
}
