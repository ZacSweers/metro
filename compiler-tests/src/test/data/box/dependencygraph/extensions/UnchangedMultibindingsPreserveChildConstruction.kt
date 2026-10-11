// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18823579

@Inject class Item
@Inject class DefaultConsumer(val items: Set<Item>, val name: String = "default")
@Inject class CyclicConsumer(val items: Set<Item>, val peer: Provider<Peer>)
@Inject class Peer(val consumer: Provider<CyclicConsumer>)
@AssistedInject class AssistedConsumer(@Assisted val name: String, val items: Set<Item>) {
  @AssistedFactory interface Factory {
    fun create(name: String): AssistedConsumer
  }
}

@DependencyGraph
interface AppGraph {
  val child: ChildGraph
  @Binds @IntoSet fun item(item: Item): Item
}

@GraphExtension
interface ChildGraph {
  val defaultConsumer: DefaultConsumer
  val cyclicConsumer: CyclicConsumer
  val assistedFactory: AssistedConsumer.Factory
}

fun box(): String {
  val child = createGraph<AppGraph>().child
  assertEquals("default", child.defaultConsumer.name)
  assertEquals(1, child.defaultConsumer.items.size)
  val cyclic = child.cyclicConsumer
  assertEquals(1, cyclic.items.size)
  assertEquals(1, cyclic.peer().consumer().items.size)
  assertTrue(cyclic !== cyclic.peer().consumer())
  val assisted = child.assistedFactory.create("assisted")
  assertEquals("assisted", assisted.name)
  assertEquals(1, assisted.items.size)
  return "OK"
}
