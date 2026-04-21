// DONT_SORT_DECLARATIONS
@Inject class X(val y: Y)

@Inject
class Y(
  val mapOfProvidersOfX: Map<String, () -> X>,
  val mapOfProvidersOfY: Map<String, () -> Y>,
)

@DependencyGraph
interface CycleMapGraph {
  fun y(): Y

  @Binds @IntoMap @StringKey("X") val X.x: X

  @Binds @IntoMap @StringKey("Y") val Y.y: Y
}