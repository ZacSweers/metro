// https://github.com/ZacSweers/metro/issues/1366
// ENABLE_DAGGER_INTEROP
// RENDER_DIAGNOSTICS_FULL_TEXT

import javax.inject.Singleton
import dagger.Component
import dagger.Module
import dagger.Provides

@Module
class ProvidersModule(val manuallyProvidedName: String) {
  @Provides
  fun provideString(): String = "Hello $manuallyProvidedName"
}

// This is fine
@Singleton
@Component
interface AppComponent {
  val string: String

  @Component.Factory
  interface Factory {
    fun create(
      @Includes providersModule: ProvidersModule,
    ): AppComponent
  }
}

// This is not. In Dagger it was still required to be declared in the annotation, but now we follow
// Metro's validation and it's not necessary.
@Singleton
@Component(
  modules = [<!BINDING_CONTAINER_ERROR!>ProvidersModule::class<!>],
)
interface SingletonComponent {
  val string: String

  @Component.Factory
  interface Factory {
    fun create(
      @Includes providersModule: ProvidersModule,
    ): SingletonComponent
  }
}
