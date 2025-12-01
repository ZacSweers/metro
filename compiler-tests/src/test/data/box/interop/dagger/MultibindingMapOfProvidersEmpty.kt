// Regression test for https://github.com/ZacSweers/metro/issues/1455
// ENABLE_DAGGER_INTEROP
// WITH_ANVIL
import com.squareup.anvil.annotations.MergeComponent
import com.squareup.anvil.annotations.optional.SingleIn
import dagger.multibindings.Multibinds
import javax.inject.Inject
import javax.inject.Provider
import kotlin.reflect.KClass

interface Multibinding

@SingleIn(AppScope::class)
class MultibindingsReference
@Inject
constructor(val multibindings: Map<KClass<*>, Provider<Multibinding>>)

@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
interface AppGraph {
  val multibindingsReference: MultibindingsReference

  @get:Multibinds val multibindings: Map<KClass<*>, Multibinding>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.multibindingsReference.multibindings.size == 0)
  assertTrue(graph.multibindings.size == 0)
  return "OK"
}
