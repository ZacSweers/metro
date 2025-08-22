// ENABLE_DAGGER_INTEROP
import dagger.Module

@Module
class LongModule {
  @Provides fun provideLong(): Long = 3L
}

@Module(includes = [LongModule::class])
class IntModule {
  @Provides fun provideInt(): Int = 3
}

@Module(includes = [IntModule::class])
class StringModule(val string: String) {
  @Provides fun provideString(): String = string
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String
  val graphExtensionFactory: AppGraphExtension.Factory

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes stringModule: StringModule): AppGraph
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
  @Inject lateinit var int: Int
  @Inject lateinit var long: Long

  fun inject() {
    appGraph.graphExtensionFactory.create().inject(this)
  }

  fun assertValues() {
    assertEquals(3, int)
    assertEquals(3L, long)
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>()
    .create(StringModule("Hello"))

  MembersInjectedClass(graph).apply {
    inject()
    assertValues()
  }

  return "OK"
}
