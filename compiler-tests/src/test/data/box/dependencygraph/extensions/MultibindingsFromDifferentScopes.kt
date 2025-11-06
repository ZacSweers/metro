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
