// https://github.com/ZacSweers/metro/issues/853
class FetchViewModel<P> @Inject constructor(
  @Assisted private val fetch: () -> P?,
) {

  fun doFetch(): P? = fetch()

  @AssistedFactory
  interface Factory<P> {
    fun create(fetch: () -> P?): FetchViewModel<P>
  }
}

@DependencyGraph
interface AppGraph {
  val factory: FetchViewModel.Factory<Int>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val factory = graph.factory
  val vm = factory.create { 3 }
  val value = vm.doFetch()
  assertEquals(3, value)
  return "OK"
}