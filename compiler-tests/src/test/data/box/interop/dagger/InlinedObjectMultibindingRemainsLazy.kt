// Regression test for https://github.com/ZacSweers/metro/issues/2587.
// ENABLE_DAGGER_INTEROP

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet

var objectInitialized = false

open class Base

object InlinedObject : Base() {
  init {
    objectInitialized = true
  }
}

@Module
@ContributesTo(AppScope::class)
object ObjectModule {
  @Provides @IntoSet fun provideObject(): Base = InlinedObject
}

@Inject
@SingleIn(AppScope::class)
class LazySetConsumer(val objects: Lazy<Set<Base>>)

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph {
  fun objects(): Set<Base>

  fun consumerProvider(): Provider<LazySetConsumer>
}

fun box(): String {
  assertFalse(objectInitialized)

  val graph = createGraph<AppGraph>()
  assertFalse(objectInitialized)

  val consumerProvider = graph.consumerProvider()
  assertFalse(objectInitialized)

  val consumer = consumerProvider()
  assertFalse(objectInitialized)

  val objects = consumer.objects.value
  assertTrue(objectInitialized)
  assertSame(InlinedObject, objects.single())

  return "OK"
}
