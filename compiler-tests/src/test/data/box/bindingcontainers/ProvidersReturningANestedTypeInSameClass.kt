// https://github.com/ZacSweers/metro/issues/1325
@BindingContainer
@ContributesTo(AppScope::class)
object MyContainer {
  @Provides
  fun provideMyType(): MyType {
    return object : MyType {}
  }

  interface MyType
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val myType: MyContainer.MyType
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.myType)
  return "OK"
}
