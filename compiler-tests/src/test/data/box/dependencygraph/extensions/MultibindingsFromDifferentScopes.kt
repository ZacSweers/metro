// https://github.com/ZacSweers/metro/issues/982
// FILE: AppGraph.kt
@DependencyGraph(AppScope::class) interface AppGraph : NodeBindings

// FILE: ChildScope.kt
abstract class ChildScope private constructor()

// FILE: ChildGraph.kt
// This is a simple example of a child graph that can be used to scope navigation nodes
@GraphExtension(ChildScope::class)
interface ChildGraph {

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {

    fun createChildGraph(): ChildGraph
  }
}

// FILE: Nodes.kt
import kotlin.reflect.KClass

// This should represent a navigation node in the real use case
interface Node

// This node is in AppScope, and can instantiate a ChildScope
@AssistedInject
class NodeA(
  @Assisted text: String,
  val childGraphFactory: PublicChildGraphFactory,
) : Node {
  @AssistedFactory
  interface Factory : NodeFactory<NodeA> {
    override fun create(text: String): NodeA
  }
}

// This node goes into the ChildScope
@AssistedInject
class NodeB(
  @Assisted text: String,
) : Node {
  @AssistedFactory
  interface Factory : NodeFactory<NodeB> {
    override fun create(text: String): NodeB
  }
}

// Factory interface for creating nodes, parameterized by the type of Node - in our real code this
// is used with a helper function to avoid having to inject every node factory everywhere
interface NodeFactory <T:Node> {
  fun create(text: String): T
}

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@MapKey
annotation class NodeKey(val value: KClass<out Node>)

// NodeA is added to the multibindings in the AppScope
@BindingContainer
@ContributesTo(AppScope::class)
interface AppNodeModule {
  @Binds
  @IntoMap
  @NodeKey(NodeA::class)
  fun nodeAFactory(factory: NodeA.Factory): NodeFactory<*>
}

// NodeB is added to the multibindings in the ChildScope
@BindingContainer
@ContributesTo(ChildScope::class)
interface ChildNodeModule {
  @Binds
  @IntoMap
  @NodeKey(NodeB::class)
  fun nodeAFactory(factory: NodeB.Factory): NodeFactory<*>
}

// And this is implemented by the `AppGraph` to provide the multibindings for all nodes
interface NodeBindings {
  @Multibinds
  fun nodeFactories(): Map<KClass<out Node>, NodeFactory<*>>
}

// FILE: PublicChildGraphFactory.kt
// A wrapper for `ChildGraph.Factory`, in a real example this would be shared with all other modules
interface PublicChildGraphFactory {
  fun createChildGraph(): ChildGraph
}

// ... while this concrete implementation is only used within the app module, which knows about the
// actual graph
@ContributesBinding(AppScope::class)
@Inject
class DefaultPublicChildGraphFactory(private val innerFactory: ChildGraph.Factory) : PublicChildGraphFactory {
  override fun createChildGraph(): ChildGraph {
    return innerFactory.createChildGraph()
  }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  // In our code this would be behind a helper function, but has the same logic
  val nodeA = appGraph.nodeFactories()[NodeA::class]!!.create("Node A") as NodeA
  // And here is where it crashes with ClassCastException
  nodeA.childGraphFactory.createChildGraph()
  return "OK"
}

/*
@Deprecated(message = "This synthesized declaration should not be used directly", level = DeprecationLevel.HIDDEN)
class $$MetroGraph : AppGraph {
  private constructor() /* primary */ {
    super/*Any*/()
    /* <init>() */

  }

  @Multibinds
  override fun nodeFactories(): Map<KClass<out Node>, NodeFactory<*>> {
    return Companion.builder<KClass<out Node>, NodeFactory<*>>(size = 1).put(key = NodeA::class, providerOfValue = <this>.#factoryProvider).build().invoke()
  }

  /* fake */ override operator fun equals(other: Any?): Boolean

  /* fake */ override fun hashCode(): Int

  /* fake */ override fun toString(): String

  override fun createChildGraph(): ChildGraph {
    return <this>.ChildGraphImpl()
  }

  @Binds
  override fun bindsAsPublicChildGraphFactory(instance: DefaultPublicChildGraphFactory): PublicChildGraphFactory {
    return error(message = "Never called")
  }

  @DependencyGraph(scope = ChildScope::class, bindingContainers = [ChildNodeModule::class])
  inner class ChildGraphImpl : ChildGraph {
    constructor() /* primary */ {
      super/*Any*/()
      /* <init>() */

    }

    /* fake */ override operator fun equals(other: Any?): Boolean

    /* fake */ override fun hashCode(): Int

    /* fake */ override fun toString(): String

    private /* final field */ val nodeAFactory: $$MetroFactory = Companion.create(childGraphFactory = Companion.create(innerFactory = <this>))
    private /* final field */ val factoryProvider: Provider<Factory> = Companion.create(delegateFactory = <this>.#nodeAFactory)
    private /* final field */ val nodeBFactory: $$MetroFactory = Companion.create()
    private /* final field */ val factoryProvider2: Provider<Factory> = Companion.create(delegateFactory = <this>.#nodeBFactory)
  }

  private /* final field */ val thisGraphInstance: AppGraph = <this>
  private /* final field */ val appGraphProvider: Provider<AppGraph> = Companion.invoke<AppGraph>(value = <this>.#thisGraphInstance)
  private /* final field */ val nodeAFactory: $$MetroFactory = Companion.create(childGraphFactory = Companion.create(innerFactory = <this>.#appGraphProvider))
  private /* final field */ val factoryProvider: Provider<Factory> = Companion.create(delegateFactory = <this>.#nodeAFactory)
} class AppGraph$$$MetroGraph cannot be cast to class dev.zacsweers.metro.Provider
 */