// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP

// Tests in-round `@InstallIn` source classes (no Hilt KAPT). They flow through Metro's existing
// hint pipeline for modules and through HiltContributionExtension for entry points.

import dagger.Module
import dagger.Provides as DaggerProvides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class MyHiltModule {
  @DaggerProvides fun provideMessage(): String = "Hello in-round"
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MyEntryPoint {
  val message: String
}

@DependencyGraph(Singleton::class)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  val entryPoint = graph as MyEntryPoint
  assertEquals("Hello in-round", entryPoint.message)
  return "OK"
}
