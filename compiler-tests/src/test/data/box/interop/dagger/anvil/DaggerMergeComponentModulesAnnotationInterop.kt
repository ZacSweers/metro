// ENABLE_DAGGER_INTEROP
// WITH_ANVIL
// Similar to DaggerModulesAnnotationInterop.kt but covers merge components too

import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.MergeComponent
import dagger.Module

@Module
class IntModule {
  @Provides fun provideInt(): Int = 3
}

@Module
class LongModule {
  @Provides fun provideLong(): Long = 3L
}

@MergeComponent(AppScope::class, modules = [IntModule::class])
interface AppComponent {
  val int: Int
}

@ContributesSubcomponent(Unit::class, modules = [LongModule::class], parentScope = AppScope::class)
interface LoggedInComponent {
  val int: Int
  val long: Long

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInComponent(): LoggedInComponent
  }
}

fun box(): String {
  val appComponent = createGraph<AppComponent>()
  assertEquals(3, appComponent.int)
  val loggedInComponent = appComponent.createLoggedInComponent()
  assertEquals(3, loggedInComponent.int)
  assertEquals(3L, loggedInComponent.long)
  return "OK"
}
