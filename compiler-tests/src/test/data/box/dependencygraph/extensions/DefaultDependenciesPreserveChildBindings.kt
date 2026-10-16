// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18910469
data class Exporter(val name: String)

@Inject
class Adapter(
  @Named("absent") val absent: Exporter? = fallbackExporter(),
  @Named("present") val present: Exporter? = fallbackExporter(),
)

private var fallbackCalls = 0
private fun fallbackExporter(): Exporter {
  fallbackCalls++
  return Exporter("fallback")
}

@Inject
class Consumer(
  val adapters: () -> Set<Adapter>,
  val providers: Map<String, () -> Adapter>,
)

@BindingContainer
interface Bindings {
  @Binds @IntoSet fun adapter(instance: Adapter): Adapter
  @Binds @IntoMap @StringKey("adapter") fun mappedAdapter(instance: Adapter): Adapter
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val adapters: () -> Set<Adapter>
  val providers: Map<String, () -> Adapter>
  val child: ChildGraph

  @Named("absent")
  @OptionalBinding
  val absentExporter: Exporter? get() = null

  @Provides @Named("present") fun exporter(): Exporter? = Exporter("parent")
}

@GraphExtension
interface ChildGraph {
  val consumer: Consumer
  val unchanged: UnchangedGrandchild
  val changed: ChangedGrandchild
}

@GraphExtension
interface UnchangedGrandchild {
  val consumer: Consumer
}

@GraphExtension
interface ChangedGrandchild {
  val consumer: Consumer
  val unchanged: UnchangedGrandchild

  @Provides @Named("absent") fun absentExporter(): Exporter? = Exporter("child-absent")
  @Provides @Named("present") fun presentExporter(): Exporter? = Exporter("child-present")
}

fun box(): String {
  fallbackCalls = 0
  val graph = createGraph<AppGraph>()
  val child = graph.child
  val consumer = child.consumer
  assertEquals(0, fallbackCalls)

  val first = consumer.adapters().single()
  assertEquals("fallback", first.absent?.name)
  assertEquals("parent", first.present?.name)
  val second = consumer.adapters().single()
  assertTrue(first !== second)
  assertEquals(2, fallbackCalls)

  val provider = consumer.providers.getValue("adapter")
  val mappedFirst = provider()
  val mappedSecond = provider()
  assertTrue(mappedFirst !== mappedSecond)
  assertEquals("fallback", mappedFirst.absent?.name)
  assertEquals("parent", mappedSecond.present?.name)
  assertEquals(4, fallbackCalls)

  val unchanged = child.unchanged.consumer.adapters().single()
  assertEquals("fallback", unchanged.absent?.name)
  assertEquals("parent", unchanged.present?.name)
  assertEquals(5, fallbackCalls)

  val changed = child.changed
  for (localConsumer in listOf(changed.consumer, changed.unchanged.consumer)) {
    val adapter = localConsumer.adapters().single()
    assertEquals("child-absent", adapter.absent?.name)
    assertEquals("child-present", adapter.present?.name)
    val mapped = localConsumer.providers.getValue("adapter")()
    assertEquals("child-absent", mapped.absent?.name)
    assertEquals("child-present", mapped.present?.name)
  }
  assertEquals(5, fallbackCalls)
  return "OK"
}
