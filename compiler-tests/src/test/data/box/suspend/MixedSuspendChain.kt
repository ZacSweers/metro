// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ Scoped suspend bindings need SuspendDoubleCheck from runtime-coroutines; the
// kotlinx-coroutines klib trips partial-linkage errors against dev test compilers on JS.

// The canonical long-chain scenario mixing suspend and non-suspend bindings:
// - String: suspend @Provides — SuspendFactory primary factory.
// - Int: non-suspend @Provides with an unwrapped suspend dep — transitively suspend, multi-ref →
//   IR-only nested SuspendFactory in the graph.
// - Long: non-suspend @Provides, transitively suspend, SCOPED → nested factory wrapped in
//   SuspendDoubleCheck.
// - Double: suspend @Provides taking SuspendProvider<Long> (defers, but directly suspend).
// - Short: non-suspend @Provides whose Double dep is wrapped in `suspend () -> Double` — the
//   wrapper breaks the chain, so Short is NOT suspend.
// - Sink: constructor-injected, mixes an unwrapped suspend scalar with deferred suspend wrappers.

abstract class AppScope private constructor()

@Inject
class Sink(
  val short: Short,
  val longProvider: SuspendProvider<Long>,
  val intProviderFn: suspend () -> Int,
  val doubleScalar: Double,
)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  suspend fun sink(): Sink

  @Provides suspend fun provideString(): String = "hallo"

  @Provides fun provideInt(s: String): Int = s.length

  @Provides @SingleIn(AppScope::class) fun provideLong(i: Int): Long = i.toLong()

  @Provides suspend fun provideDouble(l: SuspendProvider<Long>): Double = l().toDouble()

  @Provides fun provideShort(d: suspend () -> Double): Short = 7

  // Force multi-ref on Int and Long
  suspend fun intValue(): Int

  suspend fun longValue(): Long
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph)
  return "OK"
}
