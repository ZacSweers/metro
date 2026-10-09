// ENABLE_DAGGER_INTEROP
// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18823579
import dagger.Component
import dagger.Subcomponent
import dagger.Module
import dagger.Binds
import dagger.multibindings.IntoSet
import javax.inject.Inject

interface Adapter
class FirstAdapter @Inject constructor() : Adapter
class SecondAdapter @Inject constructor() : Adapter
class ThirdAdapter @Inject constructor() : Adapter
class Handler @Inject constructor(val adapters: Set<Adapter>)
class Activity {
  @Inject lateinit var handler: Handler
}

@Module
interface Bindings {
  @Binds @IntoSet fun first(adapter: FirstAdapter): Adapter
  @Binds @IntoSet fun second(adapter: SecondAdapter): Adapter
}

@Component(modules = [Bindings::class])
interface AppComponent {
  val adapters: Set<Adapter>
  val childFactory: ChildComponent.Factory
}

@Subcomponent
interface ChildComponent {
  fun inject(activity: Activity)
  val grandchildFactory: GrandchildComponent.Factory

  @Subcomponent.Factory
  interface Factory {
    fun create(): ChildComponent
  }
}

@Module
interface ExtraBindings {
  @Binds @IntoSet fun third(adapter: ThirdAdapter): Adapter
}

@Subcomponent(modules = [ExtraBindings::class])
interface GrandchildComponent {
  val handler: Handler
  @Subcomponent.Factory
  interface Factory {
    fun create(): GrandchildComponent
  }
}

fun box(): String {
  val graph = createGraph<AppComponent>()
  val child = graph.childFactory.create()
  val first = Activity()
  val second = Activity()
  child.inject(first)
  child.inject(second)
  assertTrue(first.handler !== second.handler)
  assertTrue(first.handler.adapters !== second.handler.adapters)
  assertEquals(2, first.handler.adapters.size)
  assertEquals(3, child.grandchildFactory.create().handler.adapters.size)
  return "OK"
}
