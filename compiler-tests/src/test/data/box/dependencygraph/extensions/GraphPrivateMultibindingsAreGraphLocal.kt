// Graph-private multibindings let each graph in an extension chain use the same multibinding keys
// without implicitly inheriting parent contributions.
// MODULE: lib
data class ScreenFactory(val name: String)

@GraphExtension
interface ChildGraph {
  @GraphPrivate @Multibinds val screenFactories: Set<ScreenFactory>

  @GraphPrivate
  @Provides
  @IntoSet
  fun provideChildScreenFactory(): ScreenFactory = ScreenFactory("child-set")

  @GraphPrivate @Multibinds val screenFactoryMap: Map<String, ScreenFactory>

  @GraphPrivate
  @Provides
  @IntoMap
  @StringKey("child")
  fun provideChildScreenFactoryMap(): ScreenFactory = ScreenFactory("child-map")

  fun grandChildGraph(): GrandChildGraph
}

@GraphExtension
interface GrandChildGraph {
  @GraphPrivate @Multibinds val screenFactories: Set<ScreenFactory>

  @GraphPrivate
  @Provides
  @IntoSet
  fun provideGrandChildScreenFactory(): ScreenFactory = ScreenFactory("grandchild-set")

  @GraphPrivate @Multibinds val screenFactoryMap: Map<String, ScreenFactory>

  @GraphPrivate
  @Provides
  @IntoMap
  @StringKey("grandchild")
  fun provideGrandChildScreenFactoryMap(): ScreenFactory = ScreenFactory("grandchild-map")
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface ParentGraph {
  @GraphPrivate @Multibinds val screenFactories: Set<ScreenFactory>

  @GraphPrivate
  @Provides
  @IntoSet
  fun provideParentScreenFactory(): ScreenFactory = ScreenFactory("parent-set")

  @GraphPrivate @Multibinds val screenFactoryMap: Map<String, ScreenFactory>

  @GraphPrivate
  @Provides
  @IntoMap
  @StringKey("parent")
  fun provideParentScreenFactoryMap(): ScreenFactory = ScreenFactory("parent-map")

  fun childGraph(): ChildGraph
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  assertEquals(setOf(ScreenFactory("parent-set")), parent.screenFactories)
  assertEquals(mapOf("parent" to ScreenFactory("parent-map")), parent.screenFactoryMap)

  val child = parent.childGraph()
  assertEquals(setOf(ScreenFactory("child-set")), child.screenFactories)
  assertEquals(mapOf("child" to ScreenFactory("child-map")), child.screenFactoryMap)

  val grandChild = child.grandChildGraph()
  assertEquals(setOf(ScreenFactory("grandchild-set")), grandChild.screenFactories)
  assertEquals(mapOf("grandchild" to ScreenFactory("grandchild-map")), grandChild.screenFactoryMap)

  return "OK"
}
