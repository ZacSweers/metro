import kotlin.test.*

/*
 S ← Provider<S>, Lazy<S>
 */

@Inject class X(val y: Y)

@Inject
class Y(
  val mapOfProvidersOfX: Map<String, Provider<X>>,
  val mapOfProvidersOfY: Map<String, Provider<Y>>,
)

@DependencyGraph
interface CycleMapGraph {
  fun y(): Y

  @Binds @IntoMap @StringKey("X") val X.x: X

  @Binds @IntoMap @StringKey("Y") val Y.y: Y
}

fun box(): String {
  val cycleMapGraph = createGraph<CycleMapGraph>()
  assertNotNull(cycleMapGraph.y())
  assertContains(cycleMapGraph.y().mapOfProvidersOfX, "X")
  assertNotNull(cycleMapGraph.y().mapOfProvidersOfX["X"])
  assertNotNull(cycleMapGraph.y().mapOfProvidersOfX["X"]?.invoke())
  assertNotNull(cycleMapGraph.y().mapOfProvidersOfX["X"]?.invoke()?.y)
  assertEquals(cycleMapGraph.y().mapOfProvidersOfX.size, 1)
  assertContains(cycleMapGraph.y().mapOfProvidersOfY, "Y")
  assertNotNull(cycleMapGraph.y().mapOfProvidersOfY["Y"])
  assertNotNull(cycleMapGraph.y().mapOfProvidersOfY["Y"]?.invoke())
  assertEquals(cycleMapGraph.y().mapOfProvidersOfY["Y"]!!().mapOfProvidersOfX.size, 1)
  assertEquals(cycleMapGraph.y().mapOfProvidersOfY["Y"]!!().mapOfProvidersOfY.size, 1)
  assertEquals(cycleMapGraph.y().mapOfProvidersOfY.size, 1)
  return "OK"
}