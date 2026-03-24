interface Beans

interface Cup<out B : Beans> {
  val coffee: B?
}

object EspressoBeans : Beans
class EspressoCup(override val coffee: EspressoBeans?) : Cup<EspressoBeans>

@AssistedInject
class BaristaStation<B : Beans, C : Cup<B>>(
  @Assisted private val servingCup: C?,
) {
  val coffee get() = servingCup?.coffee

  @AssistedFactory
  interface Factory<B : Beans, C : Cup<B>> {
    fun startOrder(servingCup: C?): BaristaStation<B, C>
  }
}

@DependencyGraph
interface AppGraph {
  val factory: BaristaStation.Factory<EspressoBeans, EspressoCup>
}

fun box(): String {
  val factory = createGraph<AppGraph>().factory
  assertNull(factory.startOrder(null).coffee)

  assertEquals(EspressoBeans, factory.startOrder(EspressoCup(EspressoBeans)).coffee)
  return "OK"
}
