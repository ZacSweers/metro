// ENABLE_DAGGER_INTEROP
import dagger.BindsOptionalOf
import java.util.Optional

@BindingContainer
interface Bindings {
  @BindsOptionalOf
  fun optionalString(): String
}

@DependencyGraph(AppScope::class, bindingContainers = [Bindings::class])
interface AppGraph {
  // Absent, so this is a constant InstanceFactory instead of a lambda
  val stringProvider: () -> Optional<String>
}
