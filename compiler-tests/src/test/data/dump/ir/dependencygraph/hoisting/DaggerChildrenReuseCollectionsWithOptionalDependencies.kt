// ENABLE_DAGGER_INTEROP
// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18910469
import java.util.Optional
import javax.inject.Provider

data class Exporter(val name: String)

class Adapter @javax.inject.Inject constructor(
  @Named("absent") val absent: Optional<Exporter>,
  @Named("present") val present: Optional<Exporter>,
)

class Consumer @javax.inject.Inject constructor(
  val adapters: Provider<Set<@JvmSuppressWildcards Adapter>>,
  val providers: Map<String, @JvmSuppressWildcards Provider<Adapter>>,
)

@dagger.Module
interface Bindings {
  @dagger.Binds @dagger.multibindings.IntoSet fun adapter(instance: Adapter): Adapter
  @dagger.Binds @dagger.multibindings.IntoMap @dagger.multibindings.StringKey("adapter") fun mappedAdapter(instance: Adapter): Adapter
  @dagger.BindsOptionalOf @Named("absent") fun absentExporter(): Exporter
  @dagger.BindsOptionalOf @Named("present") fun presentExporter(): Exporter

  companion object {
    @dagger.Provides @Named("present") fun exporter(): Exporter = Exporter("parent")
  }
}

@dagger.Component(modules = [Bindings::class])
interface AppGraph {
  val adapters: Provider<Set<@JvmSuppressWildcards Adapter>>
  val providers: Map<String, @JvmSuppressWildcards Provider<Adapter>>
  val childFactory: ChildGraph.Factory
}

@dagger.Subcomponent
interface ChildGraph {
  val consumer: Consumer
  val unchangedFactory: UnchangedGrandchild.Factory
  val changedFactory: ChangedGrandchild.Factory

  @dagger.Subcomponent.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@dagger.Subcomponent
interface UnchangedGrandchild {
  val consumer: Consumer

  @dagger.Subcomponent.Factory
  interface Factory {
    fun create(): UnchangedGrandchild
  }
}

@dagger.Module
object ChildBindings {
  @dagger.Provides @Named("absent") fun absentExporter(): Exporter = Exporter("child-absent")
  @dagger.Provides @Named("present") fun presentExporter(): Exporter = Exporter("child-present")
}

@dagger.Subcomponent(modules = [ChildBindings::class])
interface ChangedGrandchild {
  val consumer: Consumer
  val unchangedFactory: UnchangedGrandchild.Factory

  @dagger.Subcomponent.Factory
  interface Factory {
    fun create(): ChangedGrandchild
  }
}
