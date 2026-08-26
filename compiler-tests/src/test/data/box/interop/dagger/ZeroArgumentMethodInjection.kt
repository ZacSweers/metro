// ENABLE_DAGGER_INTEROP

class Thing {
  var initialized = false

  @javax.inject.Inject
  fun onInit() {
    initialized = true
  }
}

@DependencyGraph
interface AppGraph {
  val thingInjector: MembersInjector<Thing>
}

fun box(): String {
  val thing = Thing()
  createGraph<AppGraph>().thingInjector.injectMembers(thing)
  assertTrue(thing.initialized)
  return "OK"
}
