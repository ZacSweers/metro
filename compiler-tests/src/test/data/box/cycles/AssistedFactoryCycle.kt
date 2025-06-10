@Inject
class A(
  @Assisted nested: Boolean,
  factory: Provider<Factory>
) {
  val nestedA = if (nested) {
    factory().create(nested = false)
  } else {
    null
  }

  @AssistedFactory
  interface Factory {
    fun create(nested: Boolean): A
  }
}

@DependencyGraph
interface CycleGraph {
  fun aFactory(): A.Factory
}

fun box(): String {
  val cycleGraph = createGraph<CycleGraph>()
  val a = cycleGraph.aFactory().create(nested = true)
  assertNotNull(a.nestedA)
  assertNull(a.nestedA?.nestedA)
  return "OK"
}
