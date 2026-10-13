// ENABLE_DAGGER_INTEROP
// ENABLE_KCLASS_TO_CLASS_INTEROP
// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18889255

import dagger.Component
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import javax.inject.Provider

private var calls = 0

class TrackingHandler @Inject constructor(val tags: @JvmSuppressWildcards Provider<Map<Class<*>, Any>>)

class Activity {
  @Inject lateinit var handler: TrackingHandler
}

@Module
interface TagsModule {
  companion object {
    @Provides
    @IntoMap
    @ClassKey(String::class)
    fun tag(): Any {
      calls++
      return "TrackingTag"
    }
  }
}

@Component(modules = [TagsModule::class])
interface AppGraph {
  fun inject(activity: Activity)
}

fun box(): String {
  calls = 0
  val activity = Activity()
  createGraph<AppGraph>().inject(activity)
  assertEquals(0, calls)

  val first = activity.handler.tags.get()
  assertEquals(mapOf<Class<*>, Any>(String::class.java to "TrackingTag"), first)
  assertEquals(1, calls)

  val second = activity.handler.tags.get()
  assertEquals(first, second)
  assertTrue(first !== second)
  assertEquals(2, calls)
  return "OK"
}
