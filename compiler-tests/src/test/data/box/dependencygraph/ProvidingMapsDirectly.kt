// https://github.com/ZacSweers/metro/issues/1069
@Inject class MyIntUser(val ints: Map<String, Provider<Int>>)

@DependencyGraph
interface AppGraph {
  val user: MyIntUser

  @Named("Large")
  @Provides
  val myLargeInt: Int
    get() = 100

  @Named("Small")
  @Provides
  val mySmallInt: Int
    get() = 3

  @Provides
  @IntoSet
  fun provideLargeIntEntry(@Named("Large") int: Provider<Int>): Pair<String, Provider<Int>> =
    "Large" to int

  @Provides
  @IntoSet
  fun provideSmallIntEntry(@Named("Small") int: Provider<Int>): Pair<String, Provider<Int>> =
    "Small" to int

  @Provides
  fun provideSomeMap(ints: Set<Pair<String, Provider<Int>>>): Map<String, Provider<Int>> =
    ints.toMap()
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val user = graph.user
  assertEquals(2, user.ints.size)
  assertEquals(mapOf("Small" to 3, "Large" to 100), user.ints.mapValues { (_, v) -> v() })
  return "OK"
}
