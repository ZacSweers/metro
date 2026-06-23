// ENABLE_DAGGER_INTEROP
// GENERATE_CONTRIBUTION_PROVIDERS: false
// WITH_ANVIL
// MODULE: lib

import com.squareup.anvil.annotations.ContributesTo
import dagger.Module

abstract class AppScope

class Context(val filesDir: String)

class Thing(val path: String)

@Module
@ContributesTo(AppScope::class)
object ParentModule {
  @Provides fun provideThing(context: Context): Thing = Thing(context.filesDir)
}

// MODULE: main(lib)
// GENERATE_CONTRIBUTION_PROVIDERS: false

import dagger.Lazy

abstract class ChildScope

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val consumer: Consumer
}

class Consumer @Inject constructor(val thingLazy: Lazy<Thing>)

@DependencyGraph(AppScope::class, bindingContainers = [ParentModule::class])
interface AppGraph {
  val childGraph: ChildGraph

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides context: Context): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(Context("files"))
  assertEquals("files", graph.childGraph.consumer.thingLazy.get().path)
  return "OK"
}
