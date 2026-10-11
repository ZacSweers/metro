// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18823579

interface Adapter {
  val name: String
}

@Inject class FirstAdapter(val nameSource: NameSource) : Adapter {
  override val name: String get() = nameSource.name
}
@Inject class SecondAdapter : Adapter {
  override val name: String get() = "second"
}
@Inject class ThirdAdapter : Adapter {
  override val name: String get() = "third"
}

class NameSource(val name: String)
interface Handler {
  val adapters: Set<Adapter>
}
@Inject class TrackingHandler(override val adapters: Set<Adapter>) : Handler

@BindingContainer
object Bindings {
  @Provides fun nameSource(): NameSource = NameSource("parent")
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val adapters: Set<Adapter>
  val activity: ActivityGraph
  val sibling: SiblingGraph
  val overrideChild: OverrideGraph
  val inputChildFactory: InputGraph.Factory

  @Binds fun handler(handler: TrackingHandler): Handler
  @Binds @IntoSet fun first(adapter: FirstAdapter): Adapter
  @Binds @IntoSet fun second(adapter: SecondAdapter): Adapter
}

@GraphExtension
interface ActivityGraph {
  val handler: Handler
  val handlerProvider: Provider<Handler>
  val handlerLazy: Lazy<Handler>
  val adapters: Set<Adapter>
  val viewModel: ViewModelGraph
}

@GraphExtension
interface SiblingGraph {
  val handler: Handler
}

@GraphExtension
interface ViewModelGraph {
  val handler: Handler
  @Binds @IntoSet fun third(adapter: ThirdAdapter): Adapter
}

@GraphExtension
interface OverrideGraph {
  val handler: Handler
  @Provides fun nameSource(): NameSource = NameSource("override")
}

@GraphExtension
interface InputGraph {
  val handler: Handler
  @GraphExtension.Factory
  interface Factory {
    fun create(@Provides nameSource: NameSource): InputGraph
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val activity = graph.activity
  val first = activity.handler
  val second = activity.handler
  assertTrue(first !== second)
  assertTrue(first.adapters !== second.adapters)
  assertEquals(setOf("parent", "second"), first.adapters.map { it.name }.toSet())
  assertEquals(setOf("parent", "second"), graph.sibling.handler.adapters.map { it.name }.toSet())
  val provider = activity.handlerProvider
  val firstProvided = provider()
  val secondProvided = provider()
  assertTrue(firstProvided !== secondProvided)
  assertTrue(firstProvided.adapters !== secondProvided.adapters)
  val lazy = activity.handlerLazy
  assertTrue(lazy.value === lazy.value)
  assertEquals(setOf("parent", "second", "third"), activity.viewModel.handler.adapters.map { it.name }.toSet())
  assertEquals(setOf("override", "second"), graph.overrideChild.handler.adapters.map { it.name }.toSet())
  assertEquals(setOf("input", "second"), graph.inputChildFactory.create(NameSource("input")).handler.adapters.map { it.name }.toSet())
  return "OK"
}
