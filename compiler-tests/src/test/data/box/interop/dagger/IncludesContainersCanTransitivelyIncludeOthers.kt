// Not really a dagger-specific case but repro case used it
// https://github.com/ZacSweers/metro/issues/983
// ENABLE_DAGGER_INTEROP
import dagger.Module

data class ExtensionType(val value: Int)

@Module()
class ExtensionModule {
  @Provides fun provideExtensionType(): ExtensionType = ExtensionType(42)
}

@Module(includes = [ExtensionModule::class])
class AppGraphModule(val string: String) {
  @Provides fun provideString(): String = string
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String
  val graphExtensionFactory: AppGraphExtension.Factory

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes module: AppGraphModule): AppGraph
  }
}

@GraphExtension(Nothing::class)
interface AppGraphExtension {
  fun inject(cls: MembersInjectedClass)

  @GraphExtension.Factory
  interface Factory {
    fun create(): AppGraphExtension
  }
}

class MembersInjectedClass(
  val appGraph: AppGraph
) {
  @Inject lateinit var extensionType: ExtensionType

  fun inject() {
    appGraph.graphExtensionFactory.create().inject(this)
  }

  fun assertValues() {
    assertNotNull(extensionType)
    assertEquals(extensionType, ExtensionType(42))
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>()
    .create(AppGraphModule("Hello"))

  MembersInjectedClass(graph).apply {
    inject()
    assertValues()
  }

  return "OK"
}
