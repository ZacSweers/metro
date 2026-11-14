// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MODULE: lib
interface Cache<T> {
  val value: T
  val entries: List<T>
}

@ContributesBinding(AppScope::class)
@Inject
internal class ValueCache<T : Comparable<T>>(
  override val value: T,
  override val entries: List<T> = emptyList(),
) : Cache<T>

abstract class UserScope

@HasMemberInjections
abstract class Base<T>(val value: T) {
  @Inject var injected: Provider<T>? = null
  val member: T get() = injected!!()
}

@ContributesBinding(AppScope::class)
@ContributesBinding(UserScope::class)
@SingleIn(AppScope::class)
@Inject
internal class SharedCache<T : Comparable<T>>(value: T) : Base<T>(value)

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val strings: Cache<String>
  val ints: Cache<Int>
  val scopedStrings: Base<String>
  val scopedInts: Base<Int>

  @Provides fun string(): String = "value"
  @Provides fun int(): Int = 42
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("value", graph.strings.value)
  assertEquals(42, graph.ints.value)
  assertTrue(graph.strings.entries.isEmpty())
  assertTrue(graph.ints.entries.isEmpty())
  assertEquals("value", graph.scopedStrings.value)
  assertEquals(42, graph.scopedInts.value)
  assertEquals("value", graph.scopedStrings.member)
  assertEquals(42, graph.scopedInts.member)
  assertSame(graph.scopedStrings, graph.scopedStrings)
  assertSame(graph.scopedInts, graph.scopedInts)
  assertNotSame<Any>(graph.scopedStrings, graph.scopedInts)
  return "OK"
}
