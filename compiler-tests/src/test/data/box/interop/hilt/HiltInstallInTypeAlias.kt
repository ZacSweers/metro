// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP

import dagger.Module
import dagger.Provides as DaggerProvides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

typealias SingletonComponentAlias = SingletonComponent

@Module
@InstallIn(SingletonComponentAlias::class)
class AliasedHiltModule {
  @DaggerProvides fun provideMessage(): String = "Hello alias"
}

@EntryPoint
@InstallIn(SingletonComponentAlias::class)
interface AliasedEntryPoint {
  val message: String
}

@DependencyGraph(Singleton::class)
interface AppGraph

fun box(): String {
  val entryPoint = createGraph<AppGraph>() as AliasedEntryPoint
  assertEquals("Hello alias", entryPoint.message)
  return "OK"
}
