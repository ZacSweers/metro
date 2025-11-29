// https://github.com/ZacSweers/metro/issues/907

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides @SingleIn(AppScope::class) fun aString(): String = "test"

  @Provides fun anInt(): Int = 1

  val childGraph: ChildGraph
}

abstract class ChildScope

@GraphExtension(ChildScope::class)
interface ChildGraph {
  fun inject(activity: MyActivity)
}

@HasMemberInjections
abstract class BaseActivity {
  @Inject lateinit var baseDep: String
}

class MyActivity : BaseActivity() {
  @Inject var concreteDep: Int = 0
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val activity = MyActivity()
  appGraph.childGraph.inject(activity)
  assertEquals(1, activity.concreteDep)
  assertEquals("test", activity.baseDep)
  return "OK"
}
