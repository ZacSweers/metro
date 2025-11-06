// ENABLE_DAGGER_INTEROP
import dagger.BindsOptionalOf
import java.util.Optional

@BindingContainer
interface Bindings {
  @BindsOptionalOf
  fun optionalString(): String
  @BindsOptionalOf
  fun optionalInt(): Int
}

@DependencyGraph(AppScope::class, bindingContainers = [Bindings::class])
interface AppGraph {
  // Two accessors
  val string: Optional<String>
  val stringProvider: Provider<Optional<String>>

  // Single accessor, inline lambda wrapper
  val intProvider: Provider<Optional<Int>>

  @Provides @SingleIn(AppScope::class) fun provideString(): String = "Hello"
  @Provides fun provideInt(): Int = 3
}