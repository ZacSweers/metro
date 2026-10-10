// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18823579

@Qualifier annotation class Named
@Inject class Item
@Inject class Registry(
  val values: Map<String, Item>,
  val providers: Map<String, Provider<Item>>,
  val lazies: Map<String, Lazy<Item>>,
  @Named val names: Set<String>,
  val empty: Map<String, Int>,
)

@BindingContainer
object Bindings {
  @Provides @IntoMap @StringKey("first") fun first(): Item = Item()
  @Provides @IntoMap @StringKey("second") fun second(): Item = Item()
  @Provides @IntoSet @Named fun name(): String = "parent"
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val values: Map<String, Item>
  val providers: Map<String, Provider<Item>>
  val lazies: Map<String, Lazy<Item>>
  @Named val names: Set<String>
  val emptyProvider: Provider<Map<String, Int>>
  val child: ChildGraph
  val sibling: SiblingGraph
  @Multibinds(allowEmpty = true) val empty: Map<String, Int>
}

@GraphExtension
interface ChildGraph {
  val registry: Registry
  val values: Map<String, Item>
  val providers: Map<String, Provider<Item>>
  val lazies: Map<String, Lazy<Item>>
  val provider: Provider<Map<String, Provider<Item>>>
  val lazy: Lazy<Map<String, Item>>
  val grandchild: GrandchildGraph
}

@GraphExtension
interface SiblingGraph {
  val registry: Registry
}

@BindingContainer
object ExtraBindings {
  @Provides @IntoMap @StringKey("third") fun third(): Item = Item()
}

@GraphExtension(bindingContainers = [ExtraBindings::class])
interface GrandchildGraph {
  val registry: Registry
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val child = graph.child
  val registry = child.registry
  assertTrue(registry !== child.registry)
  assertEquals(setOf("first", "second"), registry.values.keys)
  assertEquals(registry.values.keys, registry.providers.keys)
  assertEquals(registry.values.keys, registry.lazies.keys)
  assertEquals(setOf("parent"), registry.names)
  assertTrue(registry.empty.isEmpty())
  assertTrue(registry.providers.getValue("first")() !== registry.providers.getValue("first")())
  val itemLazy = registry.lazies.getValue("first")
  assertTrue(itemLazy.value === itemLazy.value)
  assertEquals(registry.values.keys, child.values.keys)
  assertEquals(registry.values.keys, child.providers.keys)
  assertEquals(registry.values.keys, child.lazies.keys)
  assertEquals(registry.values.keys, child.provider().keys)
  val mapLazy = child.lazy
  assertTrue(mapLazy.value === mapLazy.value)
  assertEquals(registry.values.keys, graph.sibling.registry.values.keys)
  assertEquals(setOf("first", "second", "third"), child.grandchild.registry.values.keys)
  return "OK"
}
