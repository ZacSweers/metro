@DependencyGraph(AppScope::class)
interface MainGraph {
  val childGraph: ChildGraph
}

@GraphExtension(bindingContainers = [ChildModule::class])
interface ChildGraph {
  fun injectFoo(instance: Foo)
}

@BindingContainer
object ChildModule {
  @Provides fun provideTitle(): String = "Foo"
}

class Foo {
  @Inject lateinit var title: String
}

fun box(): String {
  val foo = Foo()
  createGraph<MainGraph>().childGraph.injectFoo(foo)
  assertEquals("Foo", foo.title)
  return "OK"
}
