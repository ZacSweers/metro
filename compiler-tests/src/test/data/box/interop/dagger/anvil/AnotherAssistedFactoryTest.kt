// ENABLE_DAGGER_INTEROP
// WITH_ANVIL
// MODULE: lib
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class MyAssistedType @AssistedInject constructor(
  @Assisted string: String
) {
  @AssistedFactory
  interface Factory {
    fun create(
      string: String
    ): MyAssistedType
  }
}

// MODULE: main(lib)
import com.squareup.anvil.annotations.MergeComponent

@MergeComponent(AppScope::class)
interface AppGraph {
  val factory: MyAssistedType.Factory
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.factory.create("Hello"))
  return "OK"
}