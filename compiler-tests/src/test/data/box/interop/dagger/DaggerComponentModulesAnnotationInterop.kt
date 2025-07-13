// ENABLE_DAGGER_INTEROP
// Similar to DaggerModulesAnnotationInterop.kt but covers components too
import dagger.Component
import dagger.Module

@Module
class IntModule {
  @Provides fun provideInt(): Int = 3
}

@Component(modules = [IntModule::class])
interface AppComponent {
  val int: Int
}

fun box(): String {
  val graph = createGraph<AppComponent>()
  assertEquals(3, graph.int)
  return "OK"
}
