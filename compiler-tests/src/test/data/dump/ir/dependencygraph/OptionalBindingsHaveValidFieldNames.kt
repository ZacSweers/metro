// ENABLE_DAGGER_INTEROP
import java.util.Optional

@DependencyGraph(AppScope::class, bindingContainers = [Bindings::class])
interface AppGraph {
  val optional: Optional<String>

  @Provides @SingleIn(AppScope::class) val string: String get() = "Hello"
}

@dagger.Module
interface Bindings {
  @dagger.BindsOptionalOf
  fun string(): String
}