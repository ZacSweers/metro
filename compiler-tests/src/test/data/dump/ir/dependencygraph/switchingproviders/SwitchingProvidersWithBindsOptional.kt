// ENABLE_SWITCHING_PROVIDERS: true
// ENABLE_DAGGER_INTEROP
import dagger.BindsOptionalOf
import java.util.Optional

@BindingContainer
interface Bindings {
  @BindsOptionalOf
  fun optionalInt(): Int
}

@DependencyGraph(AppScope::class, bindingContainers = [Bindings::class])
interface AppGraph {
  val int: Provider<Optional<Int>>
  val int2: Provider<Optional<Int>>

  @SingleIn(AppScope::class)
  @Provides
  fun provideInt(): Int = 3
}
