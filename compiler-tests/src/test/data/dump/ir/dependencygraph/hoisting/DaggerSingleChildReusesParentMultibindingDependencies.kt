// ENABLE_DAGGER_INTEROP
// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18858559

interface Adapter
class FirstAdapter @javax.inject.Inject constructor() : Adapter
class SecondAdapter @javax.inject.Inject constructor() : Adapter
class ThirdAdapter @javax.inject.Inject constructor() : Adapter

interface Value
class ParentValue @javax.inject.Inject constructor() : Value
class ChildValue @javax.inject.Inject constructor() : Value

interface Handler
class TrackingHandler @javax.inject.Inject constructor(
  val adapters: Set<@JvmSuppressWildcards Adapter>,
  val values: Map<String, Value>,
  val providers: Map<String, @JvmSuppressWildcards javax.inject.Provider<Value>>,
) : Handler

class Activity {
  @javax.inject.Inject lateinit var handler: Handler
}

@dagger.Module
interface ParentBindings {
  @dagger.Binds fun handler(instance: TrackingHandler): Handler
  @dagger.Binds @dagger.multibindings.IntoSet fun first(instance: FirstAdapter): Adapter
  @dagger.Binds @dagger.multibindings.IntoSet fun second(instance: SecondAdapter): Adapter
  @dagger.Binds @dagger.multibindings.IntoMap @dagger.multibindings.StringKey("parent") fun value(instance: ParentValue): Value
}

@dagger.Component(modules = [ParentBindings::class])
interface AppGraph {
  val adapters: Set<Adapter>
  val values: Map<String, Value>
  val providers: Map<String, javax.inject.Provider<Value>>
  val childFactory: ChildGraph.Factory
}

@dagger.Subcomponent
interface ChildGraph {
  fun inject(activity: Activity)
  val grandchildFactory: GrandchildGraph.Factory

  @dagger.Subcomponent.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@dagger.Module
interface ChildBindings {
  @dagger.Binds @dagger.multibindings.IntoSet fun third(instance: ThirdAdapter): Adapter
  @dagger.Binds @dagger.multibindings.IntoMap @dagger.multibindings.StringKey("child") fun value(instance: ChildValue): Value
}

@dagger.Subcomponent(modules = [ChildBindings::class])
interface GrandchildGraph {
  val handler: Handler

  @dagger.Subcomponent.Factory
  interface Factory {
    fun create(): GrandchildGraph
  }
}
