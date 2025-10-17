// https://github.com/ZacSweers/metro/issues/1167
// ENABLE_DAGGER_INTEROP

import javax.inject.Singleton
import dagger.Component
import dagger.Module
import dagger.Provides

class Dependencies {
  val int: Int = 3
}

@Module
class ProvidersModule {
  @Provides
  fun provideString(): String = "Hello"
}

@Singleton
@Component(
  modules = [ProvidersModule::class],
  dependencies = [Dependencies::class]
)
interface AppComponent {
  val int: Int
  val string: String

  @Component.Factory
  interface Factory {
    fun create(
      @Includes dependencies: Dependencies,
    ): AppComponent
  }
}

fun box(): String {
  val graph = createGraphFactory<AppComponent.Factory>().create(Dependencies())
  assertEquals(3, graph.int)
  assertEquals("Hello", graph.string)
  return "OK"
}