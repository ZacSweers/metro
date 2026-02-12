data class Dependency(val name: String)

@DependencyGraph(scope = AppScope::class)
interface AppGraph {
  val contributedInterface: ContributedInterface
}

@Qualifier annotation class MyScope
@MyScope
@GraphExtension(scope = MyScope::class)
interface MyGraph {

  @Named("sameFunctionName")
  fun dependency(): Dependency

  @Named("otherFunctionName")
  // this has a different name than the one from MyModule
  fun otherDependency(): Dependency

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createMyGraph(): MyGraph
  }
}
@ContributesTo(MyScope::class)
interface MyModule {
  @Provides
  @MyScope
  @Named("sameFunctionName")
  fun dependency(): Dependency = Dependency("regular")

  @Provides
  @MyScope
  @Named("otherFunctionName")
  fun dependency2(): Dependency = Dependency("regular")
}


fun box(): String {

  val myGraph = createGraph<AppGraph>().createMyGraph()
  val firstNonClashing = myGraph.otherDependency()
  val secondNonClashing = myGraph.otherDependency()
  val firstClashing = myGraph.dependency()
  val secondClashing = myGraph.dependency()

  assertEquals(firstNonClashing, secondNonClashing)
  assertTrue(firstNonClashing === secondNonClashing)

  assertEquals(firstClashing, secondClashing)
  assertTrue(firstClashing === secondClashing)

  return "OK"
}
