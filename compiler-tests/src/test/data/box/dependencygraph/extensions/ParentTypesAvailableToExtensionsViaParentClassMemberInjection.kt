// https://github.com/ZacSweers/metro/issues/1176
@HasMemberInjections
abstract class BaseActivity {
  @Inject lateinit var value: String
}

class Activity : BaseActivity() {
  fun onAttach(appGraph: AppGraph) {
    appGraph.extensionGraph.inject(this)
  }
}

@BindingContainer
class AppInfoModule {
  @Provides fun provideString(): String = "Hello"
}

@DependencyGraph(bindingContainers = [AppInfoModule::class])
interface AppGraph {
  val extensionGraph: ExtensionGraph
}

@GraphExtension
interface ExtensionGraph {
  fun inject(activity: Activity)
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val activity = Activity()
  activity.onAttach(appGraph)
  assertEquals("Hello", activity.value)
  return "OK"
}
