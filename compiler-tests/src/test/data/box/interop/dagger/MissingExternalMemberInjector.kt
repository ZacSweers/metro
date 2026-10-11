// MODULE: lib
class PlainTarget {
  var fallbackUsed: Boolean = false
}

@HasMemberInjections
class RealTarget {
  @Inject lateinit var value: String
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP

object PlainFallback : MembersInjector<PlainTarget> {
  override fun injectMembers(instance: PlainTarget) {
    instance.fallbackUsed = true
  }
}

// Both graphs request an injector for the same external class without injectable members.
@DependencyGraph
interface FirstGraph {
  val plainTarget: PlainTarget
  val realInjector: MembersInjector<RealTarget>

  @Provides
  fun plainTarget(injector: MembersInjector<PlainTarget> = PlainFallback): PlainTarget {
    return PlainTarget().also(injector::injectMembers)
  }

  @Provides fun value(): String = "first"
}

@DependencyGraph
interface SecondGraph {
  val plainTarget: PlainTarget
  val realInjector: MembersInjector<RealTarget>

  @Provides
  fun plainTarget(injector: MembersInjector<PlainTarget> = PlainFallback): PlainTarget {
    return PlainTarget().also(injector::injectMembers)
  }

  @Provides fun value(): String = "second"
}

fun box(): String {
  val first = createGraph<FirstGraph>()
  val second = createGraph<SecondGraph>()
  assertTrue(first.plainTarget.fallbackUsed)
  assertTrue(second.plainTarget.fallbackUsed)

  // Missing injectors must leave other classes' real member injections intact.
  val firstTarget = RealTarget().also(first.realInjector::injectMembers)
  val secondTarget = RealTarget().also(second.realInjector::injectMembers)
  assertEquals("first", firstTarget.value)
  assertEquals("second", secondTarget.value)
  return "OK"
}
