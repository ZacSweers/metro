@Inject class Dependency

@Inject class SharedByAll(val dependency: Dependency)

@Inject class SharedByGrandchildren(val dependency: Dependency)

@SingleIn(AppScope::class) @Inject class AppSingleton

@Inject class SharedByCAndE(val singleton: AppSingleton)

@GraphExtension
interface GrandchildC {
  val sharedByAll: SharedByAll
  val sharedByGrandchildren: SharedByGrandchildren
  val sharedByCAndE: SharedByCAndE
  val strings: Set<String>
  val longs: Set<Long>

  @GraphExtension.Factory
  interface Factory {
    fun createGrandchildC(): GrandchildC
  }
}

@GraphExtension
interface GrandchildD {
  val sharedByAll: SharedByAll
  val sharedByGrandchildren: SharedByGrandchildren
  val strings: Set<String>
  val longs: Set<Long>

  @GraphExtension.Factory
  interface Factory {
    fun createGrandchildD(): GrandchildD
  }
}

@GraphExtension
interface Child : GrandchildC.Factory, GrandchildD.Factory {
  @Provides @IntoSet fun provideChildLong(): Long = 2L

  @GraphExtension.Factory
  interface Factory {
    fun createChild(): Child
  }
}

@GraphExtension
interface ChildE {
  val sharedByAll: SharedByAll
  val sharedByCAndE: SharedByCAndE
  val strings: Set<String>
  val longs: Set<Long>

  @GraphExtension.Factory
  interface Factory {
    fun createChildE(): ChildE
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph : Child.Factory, ChildE.Factory {
  @Provides fun provideInt(): Int = 3

  @Provides @IntoSet fun provideOne(int: Int): String = "one$int"

  @Provides @IntoSet fun provideTwo(int: Int): String = "two$int"

  @Provides @IntoSet fun provideAppLong(): Long = 1L
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val child = graph.createChild()
  val c = child.createGrandchildC()
  val d = child.createGrandchildD()
  val e = graph.createChildE()
  assertEquals(setOf("one3", "two3"), c.strings)
  assertEquals(setOf("one3", "two3"), d.strings)
  assertEquals(setOf("one3", "two3"), e.strings)
  assertEquals(setOf(1L, 2L), c.longs)
  assertEquals(setOf(1L, 2L), d.longs)
  assertEquals(setOf(1L), e.longs)
  // Unscoped bindings still build a new instance every time
  assertNotSame(c.sharedByAll, d.sharedByAll)
  assertNotSame(c.sharedByAll, e.sharedByAll)
  assertNotSame(c.sharedByGrandchildren, d.sharedByGrandchildren)
  assertNotSame(c.sharedByCAndE, e.sharedByCAndE)
  assertSame(c.sharedByCAndE.singleton, e.sharedByCAndE.singleton)
  return "OK"
}
