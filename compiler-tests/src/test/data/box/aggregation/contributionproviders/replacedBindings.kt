abstract class ChildScope private constructor()
abstract class GrandChildScope private constructor()

interface Dependency

@DependencyGraph(AppScope::class)
interface AppGraph {
    val childGraph: ChildGraph

    val dependency: Dependency
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val grandChildGraph: GrandChildGraph
  val dependency: Dependency
}

@GraphExtension(GrandChildScope::class)
interface GrandChildGraph {
  val dependency: Dependency
}

@ContributesBinding(AppScope::class)
object UnscopedDependency : Dependency

@ContributesBinding(ChildScope::class)
@SingleIn(ChildScope::class)
object ScopedDependency : Dependency

fun box(): String {
    val appGraph = createGraph<AppGraph>()
    val childGraph = appGraph.childGraph
    val grandChildGraph = childGraph.grandChildGraph

    assertTrue(appGraph.dependency is UnscopedDependency)
    assertTrue(childGraph.dependency is ScopedDependency)
    assertTrue(grandChildGraph.dependency is ScopedDependency)
    return "OK"
}