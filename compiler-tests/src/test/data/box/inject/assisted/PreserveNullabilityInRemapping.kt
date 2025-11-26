// https://github.com/ZacSweers/metro/issues/853
class FetchViewModel<P>
@AssistedInject
constructor(
  @Assisted private val fetch: () -> P?,
  @Assisted private val fetchNotNull: () -> P & Any,
) {

  fun doFetch(): P? = fetch()

  fun doFetchNotNull(): P = fetchNotNull()

  @AssistedFactory
  interface Factory<P> {
    fun create(fetch: () -> P?, fetchNotNull: () -> P & Any): FetchViewModel<P>
  }
}

@DependencyGraph
interface AppGraph {
  val factory: FetchViewModel.Factory<Int>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val factory = graph.factory
  val vm = factory.create({ null }) { 3 }
  assertEquals(null, vm.doFetch())
  assertEquals(3, vm.doFetchNotNull())
  return "OK"
}
