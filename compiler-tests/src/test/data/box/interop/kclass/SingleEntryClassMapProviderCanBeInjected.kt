// ENABLE_KCLASS_TO_CLASS_INTEROP
// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18889255

private var calls = 0

@Inject
class TrackingHandler(val tags: () -> Map<Class<*>, Any>)

class Activity {
  @Inject lateinit var handler: TrackingHandler
}

@DependencyGraph
interface AppGraph {
  fun inject(activity: Activity)

  @Provides
  @IntoMap
  @ClassKey(String::class)
  fun tag(): Any {
    calls++
    return "TrackingTag"
  }
}

fun box(): String {
  calls = 0
  val activity = Activity()
  createGraph<AppGraph>().inject(activity)
  assertEquals(0, calls)

  val first = activity.handler.tags()
  assertEquals(mapOf<Class<*>, Any>(String::class.java to "TrackingTag"), first)
  assertEquals(1, calls)

  val second = activity.handler.tags()
  assertEquals(first, second)
  assertTrue(first !== second)
  assertEquals(2, calls)
  return "OK"
}
