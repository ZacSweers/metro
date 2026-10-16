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
