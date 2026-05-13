interface MyType {
  val tag: String
}

@ContributesBinding(AppScope::class)
@Inject
class MyTypeImpl : MyType {
  override val tag: String = "OK"
}

@MergeContributionsInIr
@DependencyGraph(scope = AppScope::class)
interface AppGraph {
  val myType: MyType
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("OK", graph.myType.tag)
  return "OK"
}
