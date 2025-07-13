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

@Module
class StringModule(val string: String) {
  @Provides fun provideString(): String = string
}

@DependencyGraph(AppScope::class, bindingContainers = [IntModule::class])
interface AppGraph {
  val int: Int
  val long: Long
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes stringModule: StringModule): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(StringModule("Hello"))
  assertEquals(3, graph.int)
  assertEquals(3L, graph.long)
  assertEquals("Hello", graph.string)
  return "OK"
}
