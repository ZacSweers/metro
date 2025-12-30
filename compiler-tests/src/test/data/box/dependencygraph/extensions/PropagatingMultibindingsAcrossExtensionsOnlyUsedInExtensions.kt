@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides @SingleIn(AppScope::class) @IntoMap @IntKey(3) fun provideIntValue(): Int = 3

  @Provides @SingleIn(AppScope::class) @IntoMap @WrappedKey(3) fun provideWrappedInt(): Int = 3

  @Provides @SingleIn(AppScope::class) @IntoSet fun provideInt(): Int = 3

  @Provides
  @SingleIn(AppScope::class)
  @ElementsIntoSet
  fun provideLongs(): Collection<Long> = setOf(3L)

  val childGraph: ChildGraph
}

@MapKey(unwrapValue = false) annotation class WrappedKey(val value: Int)

@GraphExtension
interface ChildGraph {
  val holder: Holder
}

@Inject
class Holder(
  val ints: Set<Int>,
  val longs: Set<Long>,
  val intsMap: Map<Int, Int>,
  val wrappedIntsMap: Map<WrappedKey, Int>,
)

fun box(): String {
  val graph = createGraph<AppGraph>().childGraph

  assertEquals(setOf(3), graph.holder.ints)
  assertEquals(setOf(3L), graph.holder.longs)
  assertEquals(mapOf(3 to 3), graph.holder.intsMap)
  assertEquals(mapOf(WrappedKey(3) to 3), graph.holder.wrappedIntsMap)

  return "OK"
}
