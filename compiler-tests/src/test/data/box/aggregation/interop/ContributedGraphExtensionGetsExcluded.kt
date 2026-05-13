// WITH_ANVIL
// ENABLE_DAGGER_INTEROP

import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.MergeComponent
import dagger.Component

object LoggedInScope

@ContributesSubcomponent(LoggedInScope::class, parentScope = AppScope::class)
@SingleIn(LoggedInScope::class)
interface LoggedInComponent {
  // No binding provided.
  val userId: Int

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInComponent(): LoggedInComponent
  }
}

@ContributesSubcomponent(String::class, parentScope = AppScope::class)
@SingleIn(String::class)
interface StringComponent {
  // No binding provided.
  val someString: String

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createStringComponent(): StringComponent
  }
}


@SingleIn(AppScope::class)
@MergeComponent(
  scope = AppScope::class,
  exclude = [LoggedInComponent::class, StringComponent::class]
)
interface AppComponent {
  @Component.Factory
  interface Factory {
    fun create(): AppComponent
  }
}

fun box(): String {
  val graph = createGraphFactory<AppComponent.Factory>().create()
  return "OK"
}
