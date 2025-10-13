// https://github.com/ZacSweers/metro/issues/1176
abstract class ExtensionScope

abstract class BaseActivity {
  @Inject lateinit var appInfo: AppInfo
}

class Activity: BaseActivity() {
  fun onAttach(appGraph: AppGraph) {
    val extensionGraph = appGraph.extensionGraphFactory.create()
    extensionGraph.inject(this)
  }
}

data class AppInfo(val version: String)

@BindingContainer
class AppInfoModule {
  @Provides fun provideAppInfo(): AppInfo = AppInfo("1.0")
}

@DependencyGraph(
  scope = AppScope::class,
  bindingContainers = [AppInfoModule::class],
)
interface AppGraph {
  val extensionGraphFactory: ExtensionGraph.Factory
}

@GraphExtension(ExtensionScope::class)
interface ExtensionGraph {
  fun inject(activity: Activity)

  @GraphExtension.Factory
  interface Factory {
    fun create(): ExtensionGraph
  }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val activity = Activity()
  activity.onAttach(appGraph)

  return "OK"
}