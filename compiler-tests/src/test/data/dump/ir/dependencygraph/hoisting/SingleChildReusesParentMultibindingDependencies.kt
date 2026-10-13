// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18858559

interface Adapter
@Inject class FirstAdapter : Adapter
@Inject class SecondAdapter : Adapter
@Inject class ThirdAdapter : Adapter

interface Value
@Inject class ParentValue : Value
@Inject class ChildValue : Value

interface Handler
@Inject
class TrackingHandler(
  val adapters: Set<@JvmSuppressWildcards Adapter>,
  val values: Map<String, Value>,
  val providers: Map<String, @JvmSuppressWildcards () -> Value>,
) : Handler

class Activity {
  @Inject lateinit var handler: Handler
}

@BindingContainer
interface ParentBindings {
  @Binds fun handler(instance: TrackingHandler): Handler
  @Binds @IntoSet fun first(instance: FirstAdapter): Adapter
  @Binds @IntoSet fun second(instance: SecondAdapter): Adapter
  @Binds @IntoMap @StringKey("parent") fun value(instance: ParentValue): Value
}

@DependencyGraph(bindingContainers = [ParentBindings::class])
interface AppGraph {
  val adapters: Set<Adapter>
  val values: Map<String, Value>
  val providers: Map<String, () -> Value>
  val childFactory: ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  fun inject(activity: Activity)
  val grandchildFactory: GrandchildGraph.Factory

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@BindingContainer
interface ChildBindings {
  @Binds @IntoSet fun third(instance: ThirdAdapter): Adapter
  @Binds @IntoMap @StringKey("child") fun value(instance: ChildValue): Value
}

@GraphExtension(bindingContainers = [ChildBindings::class])
interface GrandchildGraph {
  val handler: Handler

  @GraphExtension.Factory
  interface Factory {
    fun create(): GrandchildGraph
  }
}
